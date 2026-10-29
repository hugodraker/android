/* ============================================================================
 * Android Barcode Typer
 *
 * Compile:
 *   Mac / Linux:
 *   ./gradlew assembleDebug
 *   
 *   Windows:
 *   gradlew.bat assembleDebug
 * 2026 HUGO
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 * ============================================================================ */
package com.barcodetyper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PDF417Scanner {
    
    static class CellTracker {
        int[] cw = {-1, -1, -1};
        float[] err = {999f, 999f, 999f};
        int[] votes = {0, 0, 0};
    }

    static class Point {
        float x, y;
        Point(float x, float y) { this.x = x; this.y = y; }
    }

    static class RunData {
        float[] runs;
        int firstColor;
    }

    public static PDF417Compaction.DecoderResult scanBuffer(byte[] yPlane, int w, int h, int rowStride) {
        if (w < 60 || h < 15) return null;
        PDF417Data.initTables();

        // Phase 1: Localized Integral Binarization
        byte[] bin = binarize(yPlane, w, h, rowStride);

        // Phase 2: Locate the Bounding Box Vertices
        Point[] corners = locateCorners(yPlane, w, h, rowStride);
        if (corners == null) return null;

        Point tl = corners[0], bl = corners[1], tr = corners[2], br = corners[3];

        CellTracker[][] grid = new CellTracker[90][30];
        for (int r = 0; r < 90; r++)
            for (int c = 0; c < 30; c++)
                grid[r][c] = new CellTracker();

        int[] votesCols = new int[31], votesDiv3 = new int[30];
        int[] votesMod3 = new int[3], votesEcc = new int[9];

        // Phase 3: ZXing Contour-Aligned Row Tracing
        // Calculate the physical pixel length of the left and right boundaries
        float leftLen = distance(tl, bl);
        float rightLen = distance(tr, br);
        float maxLen = Math.max(leftLen, rightLen);
        
        // Super-sample the edges (step every ~1-2 pixels down the edge)
        int steps = (int) maxLen;
        if (steps < 10) return null;

        float dxLeft = (bl.x - tl.x) / steps;
        float dyLeft = (bl.y - tl.y) / steps;
        float dxRight = (br.x - tr.x) / steps;
        float dyRight = (br.y - tr.y) / steps;

        int[] lineRuns = new int[w];

        for (int i = 0; i <= steps; i++) {
            float startX = tl.x + dxLeft * i;
            float startY = tl.y + dyLeft * i;
            float endX = tr.x + dxRight * i;
            float endY = tr.y + dyRight * i;

            // Draw a precise line between the interpolated left edge and right edge
            float dx = endX - startX;
            float dy = endY - startY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            int lineSteps = (int) dist;
            if (lineSteps < 20) continue;

            float stepX = dx / lineSteps;
            float stepY = dy / lineSteps;

            int numRuns = 0;
            int currentVal = -1;
            int runLen = 0;
            int firstColor = -1;

            // Extract binary runs exactly along the contour line
            for (int j = 0; j <= lineSteps; j++) {
                int cx = (int) (startX + stepX * j + 0.5f);
                int cy = (int) (startY + stepY * j + 0.5f);
                int val = 0;
                
                if (cx >= 0 && cx < w && cy >= 0 && cy < h) {
                    val = bin[cy * w + cx];
                }

                if (currentVal == -1) {
                    currentVal = val;
                    firstColor = val;
                    runLen = 1;
                } else if (val == currentVal) {
                    runLen++;
                } else {
                    lineRuns[numRuns++] = runLen;
                    currentVal = val;
                    runLen = 1;
                }
            }
            if (runLen > 0) lineRuns[numRuns++] = runLen;
            if (numRuns < 17) continue;

            float[] fRuns = new float[numRuns];
            for (int k = 0; k < numRuns; k++) fRuns[k] = (float) lineRuns[k];

            float[] rRuns = new float[numRuns];
            for (int k = 0; k < numRuns; k++) rRuns[k] = fRuns[numRuns - 1 - k];
            int rFirstColor = (firstColor + numRuns - 1) % 2;

            // Extract codewords using ZXing's grid-voting mechanic
            extractLineData(fRuns, firstColor, grid, votesCols, votesDiv3, votesMod3, votesEcc, false);
            extractLineData(rRuns, rFirstColor, grid, votesCols, votesDiv3, votesMod3, votesEcc, true);
        }

        int bestCols = getBestVote(votesCols), bestDiv3 = getBestVote(votesDiv3);
        int bestMod3 = getBestVote(votesMod3), bestEcc = getBestVote(votesEcc);

        if (bestCols < 1 || bestDiv3 < 0 || bestMod3 < 0 || bestEcc < 0) return null;
        int rows = 3 * bestDiv3 + bestMod3 + 1;
        if (rows < 3 || rows > 90) return null;

        int eccCount = 1 << (bestEcc + 1);
        int totalCw = rows * bestCols;
        int payloadLen = totalCw - eccCount;

        int[] workBuf = new int[1024];
        int[] erasures = new int[512]; 
        int numErasures = 0;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < bestCols; c++) {
                CellTracker cell = grid[r][c];
                int bestK = -1;
                for (int k = 0; k < 3; k++) {
                    if (cell.cw[k] == -1) continue;
                    if (bestK == -1 || cell.votes[k] > cell.votes[bestK] ||
                       (cell.votes[k] == cell.votes[bestK] && cell.err[k] < cell.err[bestK])) {
                        bestK = k;
                    }
                }
                int idx = r * bestCols + c;
                if (bestK != -1) {
                    workBuf[idx] = cell.cw[bestK];
                } else {
                    workBuf[idx] = -1;
                    if (numErasures < 512) erasures[numErasures++] = idx;
                }
            }
        }

        if (PDF417ErrorCorrection.verifyAndCorrect(workBuf, payloadLen, eccCount, erasures, numErasures)) {
            return PDF417Compaction.decodeStream(workBuf, workBuf[0]);
        }
        return null;
    }

    private static byte[] binarize(byte[] yPlane, int w, int h, int stride) {
        byte[] bin = new byte[w * h];
        int rad = Math.max(12, Math.min(w, h) / 10);
        long[] intImg = new long[(w + 1) * (h + 1)];

        for (int y = 0; y < h; y++) {
            long rowSum = 0;
            int srcRow = y * stride;
            for (int x = 0; x < w; x++) {
                rowSum += (yPlane[srcRow + x] & 0xFF);
                intImg[(y + 1) * (w + 1) + (x + 1)] = intImg[y * (w + 1) + (x + 1)] + rowSum;
            }
        }

        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - rad), y1 = Math.min(h - 1, y + rad);
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - rad), x1 = Math.min(w - 1, x + rad);
                long area = (long)(x1 - x0 + 1) * (y1 - y0 + 1);
                long sum = intImg[(y1 + 1) * (w + 1) + (x1 + 1)] - intImg[y0 * (w + 1) + (x1 + 1)]
                         - intImg[(y1 + 1) * (w + 1) + x0] + intImg[y0 * (w + 1) + x0];
                long mean = sum / area;
                
                if ((yPlane[y * stride + x] & 0xFF) < mean - (mean >> 4)) {
                    bin[row + x] = 1; 
                } else {
                    bin[row + x] = 0; 
                }
            }
        }
        return bin;
    }

    private static Point[] locateCorners(byte[] yPlane, int w, int h, int rowStride) {
        List<Point> starts = new ArrayList<>();
        List<Point> stops = new ArrayList<>();

        int step = Math.max(2, h / 60);
        int[] line = new int[w];

        for (int y = step; y < h - step; y += step) {
            for (int x = 0; x < w; x++) line[x] = yPlane[y * rowStride + x] & 0xFF;
            
            RunData rd = getSubpixelRuns(line, w);
            
            for (int i = 0; i <= rd.runs.length - 8; i++) {
                if ((rd.firstColor + i) % 2 != 1) continue;
                
                if (PDF417Matcher.checkStartPattern(Arrays.copyOfRange(rd.runs, i, i + 8), null)) {
                    float px = 0; for (int k = 0; k < i; k++) px += rd.runs[k]; 
                    starts.add(new Point(px, y));
                }
                
                if (rd.runs.length - i >= 9 && PDF417Matcher.checkStopPattern(Arrays.copyOfRange(rd.runs, i, i + 9), 9, null)) {
                    float px = 0; for (int k = 0; k < i + 9; k++) px += rd.runs[k]; 
                    stops.add(new Point(px, y));
                }
            }
        }

        if (starts.size() < 2 || stops.size() < 2) return null;

        starts.sort((a, b) -> Float.compare(a.x, b.x));
        float medStartX = starts.get(starts.size() / 2).x;
        starts.removeIf(p -> Math.abs(p.x - medStartX) > w / 4.0f);

        stops.sort((a, b) -> Float.compare(a.x, b.x));
        float medStopX = stops.get(stops.size() / 2).x;
        stops.removeIf(p -> Math.abs(p.x - medStopX) > w / 4.0f);

        if (starts.isEmpty() || stops.isEmpty()) return null;

        Point tl = starts.get(0), bl = starts.get(0);
        for (Point p : starts) {
            if (p.y < tl.y) tl = p;
            if (p.y > bl.y) bl = p;
        }

        Point tr = stops.get(0), br = stops.get(0);
        for (Point p : stops) {
            if (p.y < tr.y) tr = p;
            if (p.y > br.y) br = p;
        }

        // Expand bounds slightly to ensure full boundary capture
        tl.x -= 8; tr.x += 8;
        bl.x -= 8; br.x += 8;

        if (bl.y - tl.y < 10 || br.y - tr.y < 10) return null;

        return new Point[]{tl, bl, tr, br};
    }

    private static RunData getSubpixelRuns(int[] line, int w) {
        int[] smooth = new int[w];
        for (int x = 0; x < w; x++) {
            smooth[x] = (x == 0 || x == w - 1) ? line[x] : (line[x - 1] + line[x] * 2 + line[x + 1]) / 4;
        }
        
        int window = Math.max(24, Math.min(w, w / 5));
        int[] thresh = new int[w];
        long sum = 0;
        int half = window / 2;
        
        for (int i = -half; i <= half; i++) sum += (i >= 0 && i < w) ? smooth[i] : smooth[0];
        for (int x = 0; x < w; x++) {
            thresh[x] = (int)(sum / (half * 2 + 1));
            int left = x - half;
            int right = x + half + 1;
            sum += (right < w ? smooth[right] : smooth[w - 1]) - (left >= 0 ? smooth[left] : smooth[0]);
        }
        
        float[] tempRuns = new float[w + 2];
        int numRuns = 0, firstColor = -1, currentVal = -1;
        float lastCrossX = 0.0f;
        
        for (int x = 0; x < w; x++) {
            float diffCurr = (float) smooth[x] - (float) (thresh[x] - 4);
            int val = (diffCurr < 0.0f) ? 1 : 0;
            if (firstColor == -1) firstColor = val;
            if (currentVal == -1) currentVal = val;
            
            if (val != currentVal && x > 0) {
                float diffPrev = (float) smooth[x - 1] - (float) (thresh[x - 1] - 4);
                float denom = diffPrev - diffCurr;
                float frac = (Math.abs(denom) > 0.001f) ? (diffPrev / denom) : 0.5f;
                float crossX = (float) (x - 1) + Math.max(0.0f, Math.min(1.0f, frac));
                tempRuns[numRuns++] = crossX - lastCrossX;
                lastCrossX = crossX;
                currentVal = val;
            }
        }
        if (lastCrossX < (float) (w - 1)) tempRuns[numRuns++] = (float) (w - 1) - lastCrossX;
        
        RunData rd = new RunData();
        rd.runs = Arrays.copyOf(tempRuns, numRuns);
        rd.firstColor = firstColor == -1 ? 0 : firstColor;
        return rd;
    }

    private static float distance(Point p1, Point p2) {
        return (float) Math.sqrt(Math.pow(p1.x - p2.x, 2) + Math.pow(p1.y - p2.y, 2));
    }

    private static int getBestVote(int[] votes) {
        int bestIdx = -1, maxVotes = 0;
        for (int i = 0; i < votes.length; i++) {
            if (votes[i] > maxVotes) { maxVotes = votes[i]; bestIdx = i; }
        }
        return maxVotes >= 2 ? bestIdx : -1;
    }

    private static void extractLineData(float[] runs, int firstColor, CellTracker[][] grid, int[] vc, int[] vd, int[] vm, int[] ve, boolean isReversed) {
        int numRuns = runs.length;
        
        for (int i = 0; i <= numRuns - 33; i++) {
            if ((firstColor + i) % 2 != 1) continue;
            if (!PDF417Matcher.checkStartPattern(Arrays.copyOfRange(runs, i, i + 8), null)) continue;

            for (int j = i + 32; j <= numRuns - 9; j += 8) {
                if ((firstColor + j) % 2 != 1) continue;
                int numCw = (j - (i + 8)) / 8;
                if (numCw < 3 || numCw > 32) continue;
                if (!PDF417Matcher.checkStopPattern(Arrays.copyOfRange(runs, j, numRuns), numRuns - j, null)) continue;

                float bestScore = 9999.0f;
                int[] bestSeq = null;
                float[] bestErrs = null;
                int bestRowL = -1, bestRowR = -1;

                for (int cL = 0; cL < 3; cL++) {
                    float[] eL = new float[1];
                    int cwL = PDF417Matcher.matchCodewordRuns(Arrays.copyOfRange(runs, i + 8, i + 16), 0, cL, eL);
                    if (cwL < 0 || cwL >= 900) continue;
                    int rowL = 3 * (cwL / 30) + cL;

                    for (int cR = 0; cR < 3; cR++) {
                        float[] eR = new float[1];
                        int cwR = PDF417Matcher.matchCodewordRuns(Arrays.copyOfRange(runs, j - 8, j), 0, cR, eR);
                        if (cwR < 0 || cwR >= 900) continue;
                        int rowR = 3 * (cwR / 30) + cR;

                        if (Math.abs(rowL - rowR) > numCw / 2 + 1) continue;

                        float sumErr = eL[0] + eR[0];
                        int[] tempSeq = new int[numCw];
                        float[] tempErrs = new float[numCw];
                        tempSeq[0] = cwL; tempSeq[numCw - 1] = cwR;
                        tempErrs[0] = eL[0]; tempErrs[numCw - 1] = eR[0];

                        int validData = 0;
                        for (int col = 0; col < numCw - 2; col++) {
                            int expectedRow = (int) (rowL + (rowR - rowL) * ((col + 1.0f) / (numCw - 1.0f)) + 0.5f);
                            int c = expectedRow % 3;
                            if (c < 0) c += 3; 

                            float[] ek = new float[1];
                            int cwk = PDF417Matcher.matchCodewordRuns(Arrays.copyOfRange(runs, i + 8 + (col + 1) * 8, i + 16 + (col + 1) * 8), 0, c, ek);
                            tempSeq[col + 1] = cwk;
                            tempErrs[col + 1] = ek[0];
                            
                            if (cwk >= 0) { validData++; sumErr += ek[0]; } 
                            else sumErr += 4.0f;
                        }

                        if (validData * 4 >= (numCw - 2) * 3 && sumErr < bestScore) {
                            bestScore = sumErr;
                            bestSeq = tempSeq;
                            bestErrs = tempErrs;
                            bestRowL = rowL;
                            bestRowR = rowR;
                        }
                    }
                }

                if (bestSeq != null) {
                    int cols = numCw - 2;
                    if (cols >= 1 && cols <= 30) vc[cols]++;
                    
                    int cL = bestRowL % 3;
                    int cR = bestRowR % 3;
                    int remL = bestSeq[0] % 30, remR = bestSeq[numCw - 1] % 30;

                    if (cL == 0) vd[remL]++;
                    else if (cL == 1) { if (remL < 27) { ve[remL / 3]++; vm[remL % 3]++; } }
                    else if (cL == 2) vc[remL + 1]++;

                    if (cR == 0) vc[remR + 1]++;
                    else if (cR == 1) vd[remR]++;
                    else if (cR == 2) { if (remR < 27) { ve[remR / 3]++; vm[remR % 3]++; } }

                    for (int col = 0; col < cols; col++) {
                        int expectedRow = (int) (bestRowL + (bestRowR - bestRowL) * ((col + 1.0f) / (numCw - 1.0f)) + 0.5f);
                        if (expectedRow < 0 || expectedRow >= 90) continue;

                        int cw = bestSeq[col + 1];
                        float err = bestErrs[col + 1];
                        if (cw < 0 || cw >= 929) continue;

                        int mappedCol = isReversed ? (cols - 1 - col) : col;
                        
                        CellTracker cell = grid[expectedRow][mappedCol];
                        int slot = -1;
                        for (int k = 0; k < 3; k++) if (cell.cw[k] == cw) { slot = k; break; }
                        if (slot == -1) for (int k = 0; k < 3; k++) if (cell.cw[k] == -1) { slot = k; break; }
                        if (slot != -1) {
                            cell.cw[slot] = cw;
                            cell.votes[slot]++;
                            if (err < cell.err[slot]) cell.err[slot] = err;
                        }
                    }
                    return; 
                }
            }
        }
    }
}
