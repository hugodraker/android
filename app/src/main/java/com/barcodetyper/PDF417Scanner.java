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

import java.util.Arrays;

public class PDF417Scanner {
    
    static class CellTracker {
        int[] cw = {-1, -1, -1};
        float[] err = {999f, 999f, 999f};
        int[] votes = {0, 0, 0};
    }

    public static PDF417Compaction.DecoderResult scanBuffer(byte[] yPlane, int w, int h, int rowStride) {
        if (w < 60 || h < 15) return null;
        PDF417Data.initTables();

        // Phase 1: 2D Integral Image Binarization (Glare/Shadow Immunity)
        byte[] bin = binarize(yPlane, w, h, rowStride);

        CellTracker[][] grid = new CellTracker[90][30];
        for (int r = 0; r < 90; r++)
            for (int c = 0; c < 30; c++)
                grid[r][c] = new CellTracker();

        int[] votesCols = new int[31], votesDiv3 = new int[30];
        int[] votesMod3 = new int[3], votesEcc = new int[9];

        // Phase 2: Stochastic Dense Ray-Casting
        // Blast scanlines across the image at 31 different angles (-30 to +30 degrees)
        float[] slopes = new float[31];
        for (int i = 0; i < 31; i++) slopes[i] = (i - 15) * 0.035f; 

        int step = Math.max(1, h / 120);
        int[] lineRuns = new int[w]; 

        for (int y = step; y < h - step; y += step) {
            for (float slope : slopes) {
                int numRuns = 0;
                int currentVal = -1;
                int runLen = 0;
                int firstColor = -1;

                // Trace the angled ray across the binary image
                for (int x = 0; x < w; x++) {
                    int cy = y + (int)((x - w / 2) * slope);
                    int val = 0; // Default to white bounds
                    
                    if (cy >= 0 && cy < h) {
                        val = bin[cy * w + x];
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

                // Optimization: Skip lines that obviously didn't hit a dense barcode region
                if (numRuns < 17) continue;

                float[] fRuns = new float[numRuns];
                for(int i = 0; i < numRuns; i++) fRuns[i] = (float) lineRuns[i];

                // Phase 3: Sieve and Extract local codeword fragments
                extractLineData(fRuns, firstColor, grid, votesCols, votesDiv3, votesMod3, votesEcc);
            }
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

        // Phase 4: Compile the CellTracker votes into the final Read Buffer
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

        // Phase 5: Pass stitched buffer to Unlocked Reed-Solomon Corrector
        if (PDF417ErrorCorrection.verifyAndCorrect(workBuf, payloadLen, eccCount, erasures, numErasures)) {
            return PDF417Compaction.decodeStream(workBuf, workBuf[0]);
        }
        return null;
    }

    private static byte[] binarize(byte[] yPlane, int w, int h, int stride) {
        byte[] bin = new byte[w * h];
        int rad = Math.max(12, Math.min(w, h) / 10);
        long[] intImg = new long[(w + 1) * (h + 1)];

        // Integral Image computation for localized lighting normalization
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
                
                // Aggressive localized thresholding: 
                // Protects thin white modules from being washed out by black bleed
                if ((yPlane[y * stride + x] & 0xFF) < mean - 5) {
                    bin[row + x] = 1; // Black module
                } else {
                    bin[row + x] = 0; // White module
                }
            }
        }
        return bin;
    }

    private static int getBestVote(int[] votes) {
        int bestIdx = -1, maxVotes = 0;
        for (int i = 0; i < votes.length; i++) {
            if (votes[i] > maxVotes) { maxVotes = votes[i]; bestIdx = i; }
        }
        return maxVotes >= 2 ? bestIdx : -1;
    }

    private static void extractLineData(float[] runs, int firstColor, CellTracker[][] grid, int[] vc, int[] vd, int[] vm, int[] ve) {
        int numRuns = runs.length;
        
        for (int i = 0; i <= numRuns - 33; i++) {
            if ((firstColor + i) % 2 != 1) continue;
            
            // Relaxed Matcher verifies Start Guard Pattern
            if (!PDF417Matcher.checkStartPattern(Arrays.copyOfRange(runs, i, i + 8), null)) continue;

            for (int j = i + 32; j <= numRuns - 9; j += 8) {
                if ((firstColor + j) % 2 != 1) continue;
                int numCw = (j - (i + 8)) / 8;
                if (numCw < 3 || numCw > 32) continue;
                
                // Relaxed Matcher verifies Stop Guard Pattern
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

                    // A scanline is valid if it maintains majority consistency across the ray slice
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
                    
                    // Harvest format data from row indicators
                    int remL = bestSeq[0] % 30, remR = bestSeq[numCw - 1] % 30;
                    if (bestCluster == 0) { vd[remL]++; vc[remR + 1]++; }
                    else if (bestCluster == 1) { if (remL < 27) { ve[remL / 3]++; vm[remL % 3]++; } vd[remR]++; }
                    else if (bestCluster == 2) { vc[remL + 1]++; if (remR < 27) { ve[remR / 3]++; vm[remR % 3]++; } }

                    // Deposit validated codewords into the persistent 2D tracker grid
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
}
