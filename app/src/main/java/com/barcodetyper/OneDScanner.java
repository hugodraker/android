/* ============================================================================
 * Android Barcode Typer
 *
 * Compile:
 *   Mac / Linux:
 *   ./gradlew assembleDebug
 *   
 *   Windows:
 *   gradlew.bat assembleDebug
 *
 *  2026 HUGO
 *
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 * ============================================================================ */
package com.barcodetyper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class OneDScanner {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%*";
    
    private static final int[] CODE39_CHARACTER_ENCODINGS = {
        0x034, 0x121, 0x061, 0x160, 0x031, 0x130, 0x070, 0x025, 0x124, 0x064, 
        0x109, 0x049, 0x148, 0x019, 0x118, 0x058, 0x00D, 0x10C, 0x04C, 0x01C, 
        0x103, 0x043, 0x142, 0x013, 0x112, 0x052, 0x007, 0x106, 0x046, 0x016, 
        0x181, 0x0C1, 0x1C0, 0x091, 0x190, 0x0D0, 0x085, 0x184, 0x0C4, 0x094, 
        0x0A8, 0x0A2, 0x08A, 0x02A, 0x094 
    };
    
    private static final int ASTERISK_ENCODING = 0x094;

    public static String scanBuffer(byte[] yPlane, int w, int h, int rowStride) {
        int step = Math.max(1, h / 32);
        int[] lineGray = new int[w];
        
        float[] slopes = {0.0f, 0.088f, -0.088f, 0.176f, -0.176f, 0.264f, -0.264f};

        // ZXing Upgrade: Consensus voting map for checksum-less symbologies (Code 39)
        Map<String, Integer> code39Votes = new HashMap<>();

        for (int y = step; y < h - step; y += step) {
            for (float slope : slopes) {
                for (int x = 0; x < w; x++) {
                    int cy = y + (int) ((x - w / 2) * slope);
                    lineGray[x] = (cy >= 0 && cy < h) ? (yPlane[cy * rowStride + x] & 0xFF) : 255;
                }
                
                String code = scanLineRobust(lineGray, w, code39Votes);
                if (code != null) return code;
            }
        }
        return null;
    }

    private static String scanLineRobust(int[] grayPixels, int length, Map<String, Integer> code39Votes) {
        if (length < 60) return null;
        int[] smooth = new int[length];
        for (int x = 0; x < length; x++) {
            smooth[x] = (x == 0 || x == length - 1) ? grayPixels[x] : (grayPixels[x-1] + grayPixels[x]*2 + grayPixels[x+1]) / 4;
        }

        int window = Math.max(5, length / 15);
        int[] thresh = new int[length];
        long sum = 0;
        int half = window / 2;
        for (int i = -half; i <= half; i++) {
            sum += (i >= 0 && i < length) ? smooth[i] : smooth[0];
        }
        for (int x = 0; x < length; x++) {
            thresh[x] = (int)(sum / (half * 2 + 1));
            int left = x - half;
            int right = x + half + 1;
            sum += (right < length ? smooth[right] : smooth[length-1]) - (left >= 0 ? smooth[left] : smooth[0]);
        }

        float[] runs = new float[length + 2];
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
        
        runs[numRuns++] = ((float)(length - 1)) - lastCrossX;

        // 1. Attempt Code 39 Scan
        String code39 = tryDecodeCode39(runs, numRuns, firstColor);
        if (code39 != null) {
            // ZXing Upgrade: Require 2 identical independent reads to bypass crease/fold distortions
            int votes = code39Votes.getOrDefault(code39, 0) + 1;
            if (votes >= 2) {
                return code39; 
            }
            code39Votes.put(code39, votes);
        }

        // 2. Attempt UPC/EAN Scan (UPC has mod-10 checksum, so 1 read is safe)
        for (int i = 0; i <= numRuns - 59; i++) {
            if ((firstColor + i) % 2 != 1) continue; 
            float qzLeft = (i > 0) ? runs[i - 1] : 999.0f;
            float qzRight = (i + 59 < numRuns) ? runs[i + 59] : 999.0f;
            
            float[] r59 = new float[59];
            System.arraycopy(runs, i, r59, 0, 59);
            
            String code = tryDecodeUPCSequence(r59, qzLeft, qzRight, true);
            if (code != null) return code;
            code = tryDecodeUPCSequence(r59, qzLeft, qzRight, false);
            if (code != null) return code;
        }
        return null;
    }
    
    private static String tryDecodeCode39(float[] runs, int numRuns, int firstColor) {
        if (numRuns < 29) return null;

        int startIdx = -1;
        for (int i = 0; i < numRuns - 9; i++) {
            if ((firstColor + i) % 2 != 1) continue; 

            int pattern = toCode39Pattern(runs, i);
            if (pattern == ASTERISK_ENCODING) {
                float qzLeft = (i > 0) ? runs[i - 1] : 999.0f;
                float narrowMod = estimateNarrowWidth(runs, i);
                if (qzLeft >= narrowMod * 3.0f) {
                    startIdx = i;
                    break;
                }
            }
        }
        
        if (startIdx == -1) return null;

        StringBuilder result = new StringBuilder();
        int idx = startIdx + 9; 

        while (idx < numRuns - 9) {
            if ((firstColor + idx) % 2 != 0) return null; 
            idx++; 
            
            if (idx + 9 > numRuns) break;

            int pattern = toCode39Pattern(runs, idx);
            if (pattern == -1) return null; 
            
            char decodedChar = decodeCode39Pattern(pattern);
            if (decodedChar == '*') {
                float qzRight = (idx + 9 < numRuns) ? runs[idx + 9] : 999.0f;
                float narrowMod = estimateNarrowWidth(runs, idx);
                
                if (qzRight >= narrowMod * 3.0f && result.length() > 0) {
                    return result.toString();
                }
                return null;
            }
            
            if (decodedChar == '\0') return null; 
            
            result.append(decodedChar);
            idx += 9;
        }

        return null;
    }

    private static int toCode39Pattern(float[] runs, int startIdx) {
        float[] elements = new float[9];
        System.arraycopy(runs, startIdx, elements, 0, 9);
        
        // ZXing Upgrade: Strict Margin Sorting
        float[] sorted = Arrays.copyOf(elements, 9);
        Arrays.sort(sorted);
        
        float maxNarrow = sorted[5]; // The largest of the 6 narrow bars
        float minWide = sorted[6];   // The smallest of the 3 wide bars
        
        // If a fold distorts the width, maxNarrow and minWide will collide.
        // The wide bar must be at least 40% larger than the narrow bar.
        if (minWide < maxNarrow * 1.4f) {
            return -1; // Instantly reject distorted character
        }
        
        float threshold = (maxNarrow + minWide) / 2.0f;
        int pattern = 0;
        int wideCount = 0;
        
        for (int i = 0; i < 9; i++) {
            if (runs[startIdx + i] > threshold) {
                pattern = (pattern << 1) | 1;
                wideCount++;
            } else {
                pattern = (pattern << 1) | 0;
            }
        }
        
        if (wideCount != 3) return -1;
        
        return pattern;
    }

    private static float estimateNarrowWidth(float[] runs, int startIdx) {
        float minVal = 999.0f;
        for (int i = 0; i < 9; i++) {
            if (runs[startIdx + i] < minVal) {
                minVal = runs[startIdx + i];
            }
        }
        return minVal;
    }

    private static char decodeCode39Pattern(int pattern) {
        for (int i = 0; i < CODE39_CHARACTER_ENCODINGS.length; i++) {
            if (CODE39_CHARACTER_ENCODINGS[i] == pattern) {
                return ALPHABET.charAt(i);
            }
        }
        return '\0';
    }

    // --- Legacy UPC/EAN Engine ---

    private static String tryDecodeUPCSequence(float[] r, float qzLeft, float qzRight, boolean forward) {
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
