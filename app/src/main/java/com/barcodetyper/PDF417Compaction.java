/* ============================================================================
 * Android Barcode Scanner
 *
 *  2026 HUGO
 *
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 * ============================================================================ */
package com.barcodetyper;

import java.math.BigInteger;

public class PDF417Compaction {
    private static final String MIXED_TBL = "0123456789&\r\t,:#-.$/+%*=^";
    private static final String PUNCT_TBL = ";<>@[\\]_`~!\r\t,:\n-.$/\"|*()?{}'";

    public static class DecoderResult {
        public String payload;
        public int segmentIndex = -1;
        public String fileId = null;
        public boolean isLastSegment = false;

        public boolean isMacro() {
            return segmentIndex != -1 && fileId != null;
        }
    }

    public static DecoderResult decodeStream(int[] cwBuf, int payloadLen) {
        if (payloadLen <= 1 || payloadLen > 928 || cwBuf[0] != payloadLen) return null;
        int realEnd = payloadLen;
        while (realEnd > 1 && cwBuf[realEnd - 1] == 900) realEnd--;
        if (realEnd <= 1) return null;

        DecoderResult result = new DecoderResult();
        StringBuilder out = new StringBuilder();
        int idx = 1;

        // Pre-scan for Macro PDF417 Terminator (Last Segment Indicator)
        for (int i = 1; i < realEnd; i++) {
            if (cwBuf[i] == 922) {
                result.isLastSegment = true;
                break;
            }
        }

        while (idx < realEnd) {
            int w = cwBuf[idx];
            
            // Handle Macro PDF417 Control Block
            if (w == 928) {
                idx++;
                if (idx < realEnd) {
                    result.segmentIndex = cwBuf[idx++];
                    // Extract File ID (Numeric/Text sequence following segment index)
                    StringBuilder fileIdBuilder = new StringBuilder();
                    while (idx < realEnd && cwBuf[idx] != 922 && cwBuf[idx] < 900) {
                        fileIdBuilder.append(cwBuf[idx++]);
                    }
                    result.fileId = fileIdBuilder.toString();
                }
                continue;
            }
            
            // Skip Macro Terminator in standard data flow
            if (w == 922) {
                idx++;
                continue;
            }

            if (w == 900 || (w >= 0 && w < 900)) {
                if (w == 900) idx++;
                idx = decodeTextCompaction(cwBuf, idx, realEnd, out);
            } else if (w == 901 || w == 924) {
                idx++;
                idx = decodeByteCompaction(cwBuf, idx, realEnd, w, out);
                if (idx == -1) return null;
            } else if (w == 902) {
                idx++;
                idx = decodeNumericCompaction(cwBuf, idx, realEnd, out);
            } else if (w == 913) {
                idx++;
                if (idx < realEnd) {
                    if (cwBuf[idx] < 0 || cwBuf[idx] > 255) return null;
                    out.append((char) cwBuf[idx++]);
                }
            } else {
                return null; // Unrecognized mode latch
            }
        }
        
        result.payload = out.length() > 0 ? out.toString() : null;
        return result.payload != null ? result : null;
    }

    private static int decodeTextCompaction(int[] cw, int idx, int limit, StringBuilder out) {
        int mode = 0, savedMode = 0, isShift = 0;
        while (idx < limit) {
            int w = cw[idx];
            if (w >= 900) { if (w == 900) { idx++; continue; } break; }
            idx++;
            int[] sub = { w / 30, w % 30 };
            for (int v : sub) {
                int curMode = isShift > 0 ? (isShift == 2 ? 0 : 3) : mode;
                isShift = 0;
                if (curMode == 0) {
                    if (v < 26) out.append((char) ('A' + v));
                    else if (v == 26) out.append(' ');
                    else if (v == 27) mode = 1;
                    else if (v == 28) mode = 2;
                    else if (v == 29) { savedMode = mode; isShift = 1; }
                } else if (curMode == 1) {
                    if (v < 26) out.append((char) ('a' + v));
                    else if (v == 26) out.append(' ');
                    else if (v == 27) { savedMode = mode; isShift = 2; }
                    else if (v == 28) mode = 2;
                    else if (v == 29) { savedMode = mode; isShift = 1; }
                } else if (curMode == 2) {
                    if (v < 25) out.append(MIXED_TBL.charAt(v));
                    else if (v == 25) mode = 3;
                    else if (v == 26) out.append(' ');
                    else if (v == 27) mode = 1;
                    else if (v == 28) mode = 0;
                    else if (v == 29) { savedMode = mode; isShift = 1; }
                } else if (curMode == 3) {
                    if (v < 29) out.append(PUNCT_TBL.charAt(v));
                    else if (v == 29) mode = 0;
                    if (savedMode != mode && isShift > 0) mode = savedMode;
                }
            }
        }
        return idx;
    }

    private static int decodeByteCompaction(int[] cw, int idx, int limit, int modeLatch, StringBuilder out) {
        int endIdx = idx;
        while (endIdx < limit && cw[endIdx] < 900) endIdx++;
        int totalCw = endIdx - idx;
        if (modeLatch == 924 && (totalCw % 5) != 0) return -1;

        while (idx < endIdx) {
            int rem = endIdx - idx;
            if (rem >= 5) {
                long val = 0;
                for (int j = 0; j < 5; j++) val = val * 900L + cw[idx++];
                for (int j = 5; j >= 0; j--) out.append((char) ((val >> (j * 8)) & 0xFF));
            } else {
                while (idx < endIdx) out.append((char) cw[idx++]);
            }
        }
        return idx;
    }

    private static int decodeNumericCompaction(int[] cw, int idx, int limit, StringBuilder out) {
        while (idx < limit) {
            int[] group = new int[15];
            int count = 0;
            while (idx < limit && cw[idx] < 900 && count < 15) group[count++] = cw[idx++];
            if (count == 0) break;
            
            BigInteger val = BigInteger.ZERO;
            BigInteger base = BigInteger.valueOf(900);
            for (int i = 0; i < count; i++) {
                val = val.multiply(base).add(BigInteger.valueOf(group[i]));
            }
            String numStr = val.toString();
            if (numStr.charAt(0) == '1') out.append(numStr.substring(1));
            else out.append(numStr);
        }
        return idx;
    }
}
