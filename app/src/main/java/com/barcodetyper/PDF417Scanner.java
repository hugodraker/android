package com.barcodetyper;

import java.util.Arrays;

public class PDF417Scanner {static class CellTracker {
        int[] cw = {-1, -1, -1};
        float[] err = {999f, 999f, 999f};
        int[] votes = {0, 0, 0};
    }

    public static String scanBuffer(byte[] yPlane, int w, int h, int rowStride) {
        if (w < 60 || h < 15) return null;
        PDF417Data.initTables();

        int step = Math.max(1, Math.min(4, h / 120));
        CellTracker[][] grid = new CellTracker[90][30];
        for (int r = 0; r < 90; r++)
            for (int c = 0; c < 30; c++)
                grid[r][c] = new CellTracker();

        int[] votesCols = new int[31], votesDiv3 = new int[30];
        int[] votesMod3 = new int[3], votesEcc = new int[9];

        float[] slopes = {0.0f, 0.025f, -0.025f, 0.05f, -0.05f};
        int[] line = new int[w];

        for (int y = 2; y < h - 2; y += step) {
            for (int sIdx = 0; sIdx < slopes.length; sIdx++) {
                if (sIdx >= 3 && y % 2 != 0) continue;
                for (int x = 0; x < w; x++) {
                    int cy = sIdx == 0 ? y : y + (int) ((x - w / 2) * slopes[sIdx]);
                    line[x] = (cy >= 0 && cy < h) ? (yPlane[cy * rowStride + x] & 0xFF) : 255;
                }
                extractRowData(line, w, grid, votesCols, votesDiv3, votesMod3, votesEcc);
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
        int[] erasures = new int[64];
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
                    if (numErasures < 64) erasures[numErasures++] = idx;
                }
            }
        }

        if (PDF417ErrorCorrection.verifyAndCorrect(workBuf, payloadLen, eccCount, erasures, numErasures)) {
            return PDF417Compaction.decodeStream(workBuf, workBuf[0]);
        }
        return null;
    }

    private static int getBestVote(int[] votes) {
        int bestIdx = -1, maxVotes = 0;
        for (int i = 0; i < votes.length; i++) {
            if (votes[i] > maxVotes) { maxVotes = votes[i]; bestIdx = i; }
        }
        return maxVotes >= 2 ? bestIdx : -1;
    }

    private static void extractRowData(int[] line, int w, CellTracker[][] grid, int[] vc, int[] vd, int[] vm, int[] ve) {
    int[] smooth = new int[w];
    for (int x = 0; x < w; x++) {
        smooth[x] = (x == 0 || x == w - 1) ? line[x] : (line[x - 1] + line[x] * 2 + line[x + 1]) / 4;
    }

    int window = Math.max(24, Math.min(w, w / 5));
    int[] thresh = new int[w];
    long sum = 0;
    int half = window / 2;
    for (int i = -half; i <= half; i++) {
        sum += (i >= 0 && i < w) ? smooth[i] : smooth[0];
    }
    for (int x = 0; x < w; x++) {
        thresh[x] = (int)(sum / (half * 2 + 1));
        int left = x - half;
        int right = x + half + 1;
        sum += (right < w ? smooth[right] : smooth[w - 1]) - (left >= 0 ? smooth[left] : smooth[0]);
    }

    float[] runs = new float[w + 2];
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
            runs[numRuns++] = crossX - lastCrossX;
            lastCrossX = crossX;
            currentVal = val;
        }
    }
    if (lastCrossX < (float) (w - 1)) runs[numRuns++] = (float) (w - 1) - lastCrossX;

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
}
