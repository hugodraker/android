package com.barcodetyper;

public class OneDScanner {
    public static String scanBuffer(byte[] yPlane, int w, int h, int rowStride) {
        int step = Math.max(1, h / 32);
        int[] lineGray = new int[w];
        float[] slopes = {0.0f, 0.176f, -0.176f};

        for (int y = step; y < h - step; y += step) {
            for (float slope : slopes) {
                for (int x = 0; x < w; x++) {
                    int cy = y + (int) ((x - w / 2) * slope);
                    lineGray[x] = (cy >= 0 && cy < h) ? (yPlane[cy * rowStride + x] & 0xFF) : 255;
                }
                String code = scanLineRobust(lineGray, w);
                if (code != null) return code;
            }
        }
        return null;
    }

    private static String scanLineRobust(int[] grayPixels, int length) {
        if (length < 60) return null;
        int[] smooth = new int[length];
        for (int x = 0; x < length; x++) {
            smooth[x] = (x == 0 || x == length - 1) ? grayPixels[x] : (grayPixels[x-1] + grayPixels[x]*2 + grayPixels[x+1]) / 4;
        }

        int window = Math.max(5, length / 15);
        int[] thresh = new int[length];
        long sum = 0;
        for (int x = 0; x < window && x < length; x++) sum += smooth[x];
        for (int x = 0; x < length; x++) {
            thresh[x] = (int)(sum / window);
            int left = x - window / 2, right = x + window / 2 + 1;
            sum += (right < length ? smooth[right] : smooth[length-1]) - (left >= 0 ? smooth[left] : smooth[0]);
        }

        float[] runs = new float[length];
        int numRuns = 0, firstColor = -1, currentVal = -1;
        float lastCrossX = 0.0f;

        for (int x = 0; x < length; x++) {
            float diffCurr = (float)smooth[x] - (float)(thresh[x] - 5);
            int val = (diffCurr < 0.0f) ? 1 : 0;

            if (firstColor == -1) firstColor = val;
            if (currentVal == -1) currentVal = val;

            if (val != currentVal && x > 0) {
                float diffPrev = (float)smooth[x-1] - (float)(thresh[x-1] - 5);
                float denom = diffPrev - diffCurr;
                float frac = (Math.abs(denom) > 0.001f) ? (diffPrev / denom) : 0.5f;
                runs[numRuns++] = ((float)(x - 1) + Math.max(0.0f, Math.min(1.0f, frac))) - lastCrossX;
                lastCrossX = (float)(x - 1) + Math.max(0.0f, Math.min(1.0f, frac));
                currentVal = val;
            }
        }

        for (int i = 0; i <= numRuns - 59; i++) {
            if ((firstColor + i) % 2 != 1) continue;
            float qzLeft = (i > 0) ? runs[i - 1] : 999.0f;
            float qzRight = (i + 59 < numRuns) ? runs[i + 59] : 999.0f;
            
            float[] r59 = new float[59];
            System.arraycopy(runs, i, r59, 0, 59);
            
            String code = tryDecodeSequence(r59, qzLeft, qzRight, true);
            if (code != null) return code;
            code = tryDecodeSequence(r59, qzLeft, qzRight, false);
            if (code != null) return code;
        }
        return null;
    }

    private static String tryDecodeSequence(float[] r, float qzLeft, float qzRight, boolean forward) {
        float[] runs = new float[59];
        for (int k = 0; k < 59; k++) runs[k] = forward ? r[k] : r[58 - k];

        float modS = (runs[0] + runs[1] + runs[2]) / 3.0f;
        if (modS < 0.65f) return null;
        float modM = (runs[27] + runs[28] + runs[29] + runs[30] + runs[31]) / 5.0f;
        float modE = (runs[56] + runs[57] + runs[58]) / 3.0f;
        if (modM > modS * 1.28f || modM < modS * 0.78f) return null;
        if (modE > modS * 1.28f || modE < modS * 0.78f) return null;

        float avgMod = (modS + modM + modE) / 3.0f;
        if (qzLeft < avgMod * 4.5f || qzRight < avgMod * 4.5f) return null;

        int[] digits = new int[13];
        int gMask = 0;
        
        for (int d = 0; d < 6; d++) {
            int idx = 3 + d * 4;
            int[] outG = new int[1];
            int val = matchDigitStrict(runs[idx], runs[idx+1], runs[idx+2], runs[idx+3], true, outG);
            if (val == -1) return null;
            digits[d + 1] = val;
            gMask = (gMask << 1) | outG[0];
        }

        final int[] parityTable = { 0x00, 0x0B, 0x0D, 0x0E, 0x13, 0x19, 0x1C, 0x15, 0x16, 0x1A };
        int firstDigit = -1;
        for (int p = 0; p < 10; p++) if (gMask == parityTable[p]) { firstDigit = p; break; }
        if (firstDigit == -1) return null;
        digits[0] = firstDigit;

        for (int d = 0; d < 6; d++) {
            int idx = 32 + d * 4;
            int val = matchDigitStrict(runs[idx], runs[idx+1], runs[idx+2], runs[idx+3], false, null);
            if (val == -1) return null;
            digits[d + 7] = val;
        }

        int evenSum = 0, oddSum = 0;
        for (int j = 0; j < 12; j++) {
            if (j % 2 == 0) evenSum += digits[j];
            else oddSum += digits[j];
        }
        if (((10 - ((oddSum * 3 + evenSum) % 10)) % 10) != digits[12]) return null;

        StringBuilder sb = new StringBuilder();
        int start = (digits[0] == 0) ? 1 : 0;
        for (int j = start; j < 13; j++) sb.append((char)('0' + digits[j]));
        return sb.toString();
    }

    private static int matchDigitStrict(float r1, float r2, float r3, float r4, boolean allowG, int[] outIsG) {
        float s = r1 + r2 + r3 + r4;
        if (s < 4.5f) return -1;
        float[] n = { r1 * 7.0f / s, r2 * 7.0f / s, r3 * 7.0f / s, r4 * 7.0f / s };

        final float[][] patsL = { {3,2,1,1},{2,2,2,1},{2,1,2,2},{1,4,1,1},{1,1,3,2},{1,2,3,1},{1,1,1,4},{1,3,1,2},{1,2,1,3},{3,1,1,2} };
        final float[][] patsG = { {1,1,2,3},{1,2,2,2},{2,2,1,2},{1,1,4,1},{2,3,1,1},{1,3,2,1},{4,1,1,1},{2,1,3,1},{3,1,2,1},{2,1,1,3} };

        int bestDigit = -1, bestG = 0;
        float minErr = 999.0f, secondErr = 999.0f;

        for (int i = 0; i < 10; i++) {
            float errL = Math.abs(n[0]-patsL[i][0]) + Math.abs(n[1]-patsL[i][1]) + Math.abs(n[2]-patsL[i][2]) + Math.abs(n[3]-patsL[i][3]);
            if (errL < minErr) { secondErr = minErr; minErr = errL; bestDigit = i; bestG = 0; }
            else if (errL < secondErr) secondErr = errL;

            if (allowG) {
                float errG = Math.abs(n[0]-patsG[i][0]) + Math.abs(n[1]-patsG[i][1]) + Math.abs(n[2]-patsG[i][2]) + Math.abs(n[3]-patsG[i][3]);
                if (errG < minErr) { secondErr = minErr; minErr = errG; bestDigit = i; bestG = 1; }
                else if (errG < secondErr) secondErr = errG;
            }
        }

        if (bestDigit != -1 && minErr < 1.20f && (secondErr - minErr) >= 0.25f) {
            if (outIsG != null) outIsG[0] = bestG;
            return bestDigit;
        }
        return -1;
    }
}
