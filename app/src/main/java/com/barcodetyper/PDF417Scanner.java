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

        // Phase 1: Robust 1D Sub-Pixel Search to locate all Start/Stop pattern fragments
        Point[] corners = locateCorners(yPlane, w, h, rowStride);
        if (corners == null) return null;

        Point tl = corners[0], bl = corners[1], tr = corners[2], br = corners[3];

        // Expand bounds slightly to ensure we don't crop the outer bars of the guard patterns
        tl.x -= 4; tl.y -= 4;
        bl.x -= 4; bl.y += 4;
        tr.x += 4; tr.y -= 4;
        br.x += 4; br.y += 4;

        int flatWidth = (int) ((distance(tl, tr) + distance(bl, br)) / 2.0f) + 8;
        int flatHeight = (int) ((distance(tl, bl) + distance(tr, br)) / 2.0f) + 8;
        if (flatWidth < 50 || flatHeight < 10) return null;

        // Phase 2: Calculate Homography Projection
        Perspective transform = Perspective.quadToQuad(
            0, 0, flatWidth, 0, flatWidth, flatHeight, 0, flatHeight,
            tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y
        );
        if (transform == null) return null;

        // Phase 3: Unwarp image as Grayscale via Bilinear Interpolation (Retains Sub-Pixel Accuracy)
        int[][] flatGrid = new int[flatHeight][flatWidth];
        float[] pt = new float[2];
        for (int y = 0; y < flatHeight; y++) {
            for (int x = 0; x < flatWidth; x++) {
                pt[0] = x; pt[1] = y;
                transform.transform(pt);
                int srcX = (int) pt[0];
                int srcY = (int) pt[1];
                
                if (srcX >= 0 && srcX < w - 1 && srcY >= 0 && srcY < h - 1) {
                    float dx = pt[0] - srcX;
                    float dy = pt[1] - srcY;
                    int p00 = yPlane[srcY * rowStride + srcX] & 0xFF;
                    int p10 = yPlane[srcY * rowStride + srcX + 1] & 0xFF;
                    int p01 = yPlane[(srcY + 1) * rowStride + srcX] & 0xFF;
                    int p11 = yPlane[(srcY + 1) * rowStride + srcX + 1] & 0xFF;
                    float top = p00 * (1.0f - dx) + p10 * dx;
                    float bot = p01 * (1.0f - dx) + p11 * dx;
                    flatGrid[y][x] = (int) (top * (1.0f - dy) + bot * dy);
                } else {
                    flatGrid[y][x] = 255;
                }
            }
        }

        // Phase 4: Decode Flattened Grid using mathematical row slicing
        CellTracker[][] grid = new CellTracker[90][30];
        for (int r = 0; r < 90; r++)
            for (int c = 0; c < 30; c++)
                grid[r][c] = new CellTracker();

        int[] votesCols = new int[31], votesDiv3 = new int[30];
        int[] votesMod3 = new int[3], votesEcc = new int[9];

        int step = Math.max(1, flatHeight / 120);
        for (int y = 2; y < flatHeight - 2; y += step) {
            extractRowData(flatGrid[y], flatWidth, grid, votesCols, votesDiv3, votesMod3, votesEcc);
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
        int[] erasures = new int[256];
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
                    if (numErasures < 256) erasures[numErasures++] = idx;
                }
            }
        }

        if (PDF417ErrorCorrection.verifyAndCorrect(workBuf, payloadLen, eccCount, erasures, numErasures)) {
            return PDF417Compaction.decodeStream(workBuf, workBuf[0]);
        }
        return null;
    }

    private static Point[] locateCorners(byte[] yPlane, int w, int h, int rowStride) {
        List<Point> starts = new ArrayList<>();
        List<Point> stops = new ArrayList<>();

        int step = Math.max(2, h / 60);
        int[] line = new int[w];

        // Sweep the image to collect all visible Start and Stop pattern outer edges
        for (int y = step; y < h - step; y += step) {
            for (int x = 0; x < w; x++) line[x] = yPlane[y * rowStride + x] & 0xFF;
            
            RunData rd = getSubpixelRuns(line, w);
            
            for (int i = 0; i <= rd.runs.length - 8; i++) {
                if ((rd.firstColor + i) % 2 != 1) continue;
                
                if (PDF417Matcher.checkStartPattern(Arrays.copyOfRange(rd.runs, i, i + 8), null)) {
                    float px = 0; for (int k = 0; k < i; k++) px += rd.runs[k]; // Outer Left Bound
                    starts.add(new Point(px, y));
                }
                
                if (rd.runs.length - i >= 9 && PDF417Matcher.checkStopPattern(Arrays.copyOfRange(rd.runs, i, i + 9), 9, null)) {
                    float px = 0; for (int k = 0; k < i + 9; k++) px += rd.runs[k]; // Outer Right Bound
                    stops.add(new Point(px, y));
                }
            }
        }

        if (starts.size() < 2 || stops.size() < 2) return null;

        // Filter outliers mathematically
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

    private static void extractRowData(int[] line, int w, CellTracker[][] grid, int[] vc, int[] vd, int[] vm, int[] ve) {
        RunData rd = getSubpixelRuns(line, w);
        float[] runs = rd.runs;
        int firstColor = rd.firstColor;
        int numRuns = runs.length;

        for (int i = 0; i <= numRuns - 33; i++) {
            if ((firstColor + i) % 2 != 1) continue;
            if (!PDF417Matcher.checkStartPattern(Arrays.copyOfRange(runs, i, i + 8), null)) continue;

            for (int j = i + 32; j <= numRuns - 9; j += 8) {
                if ((firstColor + j) % 2 != 1) continue;
                int numCw = (j - (i + 8)) / 8;
                if (numCw < 3 || numCw > 32) continue;
                if (!PDF417Matcher.checkStopPattern(Arrays.copyOfRange(runs, j, numRuns), numRuns - j, null)) continue;

                int bestCluster = -1;
                float bestScore = 9999.0f;
                int[] bestSeq = new int[numCw];
                float[] bestErrs = new float[numCw];

                for (int c = 0; c < 3; c++) {
                    float[] eL = new float[1], eR = new float[1];
                    int cwL = PDF417Matcher.matchCodewordRuns(Arrays.copyOfRange(runs, i + 8, i + 16), 0, c, eL);
                    int cwR = PDF417Matcher.matchCodewordRuns(Arrays.copyOfRange(runs, j - 8, j), 0, c, eR);

                    if (cwL < 0 || cwL >= 900 || cwR < 0 || cwR >= 900) continue;
                    if ((cwL / 30) != (cwR / 30)) continue;

                    float sumErr = eL[0] + eR[0];
                    int[] tempSeq = new int[numCw];
                    float[] tempErrs = new float[numCw];
                    tempSeq[0] = cwL; tempSeq[numCw - 1] = cwR;
                    tempErrs[0] = eL[0]; tempErrs[numCw - 1] = eR[0];

                    int validData = 0;
                    for (int k = 1; k < numCw - 1; k++) {
                        float[] ek = new float[1];
                        int cwk = PDF417Matcher.matchCodewordRuns(Arrays.copyOfRange(runs, i + 8 + k * 8, i + 16 + k * 8), 0, c, ek);
                        tempSeq[k] = cwk;
                        tempErrs[k] = ek[0];
                        if (cwk >= 0) { validData++; sumErr += ek[0]; } 
                        else sumErr += 4.0f;
                    }

                    if (validData * 4 >= (numCw - 2) * 3 && sumErr < bestScore) {
                        bestScore = sumErr;
                        bestCluster = c;
                        System.arraycopy(tempSeq, 0, bestSeq, 0, numCw);
                        System.arraycopy(tempErrs, 0, bestErrs, 0, numCw);
                    }
                }

                if (bestCluster != -1) {
                    int rowNum = 3 * (bestSeq[0] / 30) + bestCluster;
                    if (rowNum < 0 || rowNum >= 90) break;
                    
                    int cols = numCw - 2;
                    if (cols >= 1 && cols <= 30) vc[cols]++;
                    
                    int remL = bestSeq[0] % 30, remR = bestSeq[numCw - 1] % 30;
                    if (bestCluster == 0) { vd[remL]++; vc[remR + 1]++; }
                    else if (bestCluster == 1) { if (remL < 27) { ve[remL / 3]++; vm[remL % 3]++; } vd[remR]++; }
                    else if (bestCluster == 2) { vc[remL + 1]++; if (remR < 27) { ve[remR / 3]++; vm[remR % 3]++; } }

                    for (int c = 0; c < cols; c++) {
                        int cw = bestSeq[c + 1];
                        float err = bestErrs[c + 1];
                        if (cw < 0 || cw >= 929) continue;
                        CellTracker cell = grid[rowNum][c];
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

    // --- Perspective Mathematics Class ---
    private static class Perspective {
        float a11, a12, a13, a21, a22, a23, a31, a32, a33;
        
        static Perspective squareToQuad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
            float dx3 = x0 - x1 + x2 - x3, dy3 = y0 - y1 + y2 - y3;
            if (Math.abs(dx3) < 1e-4f && Math.abs(dy3) < 1e-4f) {
                Perspective p = new Perspective();
                p.a11 = x1 - x0; p.a12 = x2 - x1; p.a13 = x0;
                p.a21 = y1 - y0; p.a22 = y2 - y1; p.a23 = y0;
                p.a31 = 0; p.a32 = 0; p.a33 = 1.0f;
                return p;
            } else {
                float dx1 = x1 - x2, dy1 = y1 - y2;
                float dx2 = x3 - x2, dy2 = y3 - y2;
                float det = dx1 * dy2 - dx2 * dy1;
                if (det == 0.0f) return null;
                float a31 = (dx3 * dy2 - dx2 * dy3) / det;
                float a32 = (dx1 * dy3 - dx3 * dy1) / det;
                Perspective p = new Perspective();
                p.a11 = x1 - x0 + a31 * x1;
                p.a12 = x3 - x0 + a32 * x3;
                p.a13 = x0;
                p.a21 = y1 - y0 + a31 * y1;
                p.a22 = y3 - y0 + a32 * y3;
                p.a23 = y0;
                p.a31 = a31;
                p.a32 = a32;
                p.a33 = 1.0f;
                return p;
            }
        }
        
        static Perspective quadToSquare(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
            Perspective q = squareToQuad(x0, y0, x1, y1, x2, y2, x3, y3);
            if (q == null) return null;
            Perspective p = new Perspective();
            p.a11 = q.a22 * q.a33 - q.a23 * q.a32;
            p.a21 = q.a23 * q.a31 - q.a21 * q.a33;
            p.a31 = q.a21 * q.a32 - q.a22 * q.a31;
            p.a12 = q.a13 * q.a32 - q.a12 * q.a33;
            p.a22 = q.a11 * q.a33 - q.a13 * q.a31;
            p.a32 = q.a12 * q.a31 - q.a11 * q.a32;
            p.a13 = q.a12 * q.a23 - q.a13 * q.a22;
            p.a23 = q.a13 * q.a21 - q.a11 * q.a23;
            p.a33 = q.a11 * q.a22 - q.a12 * q.a21;
            return p;
        }

        Perspective times(Perspective other) {
            Perspective p = new Perspective();
            p.a11 = a11 * other.a11 + a12 * other.a21 + a13 * other.a31;
            p.a12 = a11 * other.a12 + a12 * other.a22 + a13 * other.a32;
            p.a13 = a11 * other.a13 + a12 * other.a23 + a13 * other.a33;
            p.a21 = a21 * other.a11 + a22 * other.a21 + a23 * other.a31;
            p.a22 = a21 * other.a12 + a22 * other.a22 + a23 * other.a32;
            p.a23 = a21 * other.a13 + a22 * other.a23 + a23 * other.a33;
            p.a31 = a31 * other.a11 + a32 * other.a21 + a33 * other.a31;
            p.a32 = a31 * other.a12 + a32 * other.a22 + a33 * other.a32;
            p.a33 = a31 * other.a13 + a32 * other.a23 + a33 * other.a33;
            return p;
        }
        
        static Perspective quadToQuad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                                      float x0p, float y0p, float x1p, float y1p, float x2p, float y2p, float x3p, float y3p) {
            Perspective qToS = quadToSquare(x0, y0, x1, y1, x2, y2, x3, y3);
            Perspective sToQ = squareToQuad(x0p, y0p, x1p, y1p, x2p, y2p, x3p, y3p);
            if (qToS == null || sToQ == null) return null;
            return sToQ.times(qToS);
        }

        void transform(float[] point) {
            float x = point[0], y = point[1];
            float denominator = a31 * x + a32 * y + a33;
            point[0] = (a11 * x + a12 * y + a13) / denominator;
            point[1] = (a21 * x + a22 * y + a23) / denominator;
        }
    }
}
