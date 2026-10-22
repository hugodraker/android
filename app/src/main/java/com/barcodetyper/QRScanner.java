/* ============================================================================
 * Android Barcode Typer
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 * ============================================================================ */
package com.barcodetyper;

public class QRScanner {

    private static final int[] expTable = new int[512];
    private static final int[] logTable = new int[256];
    private static boolean gfInit = false;

    private static synchronized void initGF256() {
        if (gfInit) return;
        int x = 1;
        for (int i = 0; i < 255; i++) {
            expTable[i] = expTable[i + 255] = x;
            logTable[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D; 
        }
        expTable[510] = expTable[0];
        gfInit = true;
    }

    private static int gfMul(int a, int b) {
        return (a == 0 || b == 0) ? 0 : expTable[logTable[a] + logTable[b]];
    }

    private static int gfInv(int a) {
        return expTable[255 - logTable[a]];
    }

    private static boolean checkRatio(int[] state) {
        int total = 0;
        for (int i = 0; i < 5; i++) {
            if (state[i] == 0) return false;
            total += state[i];
        }
        if (total < 7) return false;
        int moduleSize = (total << 8) / 7;
        int maxVariance = moduleSize / 2;

        return Math.abs(moduleSize - (state[0] << 8)) < maxVariance &&
               Math.abs(moduleSize - (state[1] << 8)) < maxVariance &&
               Math.abs(3 * moduleSize - (state[2] << 8)) < 3 * maxVariance &&
               Math.abs(moduleSize - (state[3] << 8)) < maxVariance &&
               Math.abs(moduleSize - (state[4] << 8)) < maxVariance;
    }

    private static float centerFromEnd(int[] state, int end) {
        return (end - state[4] - state[3]) - state[2] / 2.0f;
    }

    private static boolean crossCheckVertical(byte[] bin, int w, int h, int startX, int centerJ, int maxCount, int[] state) {
        state[0] = state[1] = state[2] = state[3] = state[4] = 0;
        int i = centerJ;
        while (i >= 0 && bin[i * w + startX] != 0) { state[2]++; i--; }
        if (i < 0) return false;
        while (i >= 0 && bin[i * w + startX] == 0 && state[1] <= maxCount) { state[1]++; i--; }
        if (i < 0 || state[1] > maxCount) return false;
        while (i >= 0 && bin[i * w + startX] != 0 && state[0] <= maxCount) { state[0]++; i--; }
        if (state[0] > maxCount) return false;

        i = centerJ + 1;
        while (i < h && bin[i * w + startX] != 0) { state[2]++; i++; }
        if (i == h) return false;
        while (i < h && bin[i * w + startX] == 0 && state[3] <= maxCount) { state[3]++; i++; }
        if (i == h || state[3] > maxCount) return false;
        while (i < h && bin[i * w + startX] != 0 && state[4] <= maxCount) { state[4]++; i++; }
        if (state[4] > maxCount) return false;

        return checkRatio(state);
    }

    public static String scanBuffer(byte[] yPlane, int w, int h, int rowStride) {
        initGF256();

        byte[] bin = new byte[w * h];
        int rad = Math.max(12, Math.min(w, h) / 8);
        int[] intImg = new int[(w + 1) * (h + 1)];

        for (int y = 0; y < h; y++) {
            int rowSum = 0;
            int srcRow = y * rowStride;
            for (int x = 0; x < w; x++) {
                int g = yPlane[srcRow + x] & 0xFF;
                rowSum += g;
                intImg[(y + 1) * (w + 1) + (x + 1)] = intImg[y * (w + 1) + (x + 1)] + rowSum;
            }
        }

        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - rad), y1 = Math.min(h - 1, y + rad);
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - rad), x1 = Math.min(w - 1, x + rad);
                int area = (x1 - x0 + 1) * (y1 - y0 + 1);
                int sum = intImg[(y1 + 1) * (w + 1) + (x1 + 1)] - intImg[y0 * (w + 1) + (x1 + 1)]
                        - intImg[(y1 + 1) * (w + 1) + x0] + intImg[y0 * (w + 1) + x0];
                if ((yPlane[y * rowStride + x] & 0xFF) < (sum / area) - 5) {
                    bin[row + x] = 1;
                }
            }
        }

        float[] fx = new float[16];
        float[] fy = new float[16];
        float[] fmod = new float[16];
        int fCount = 0;

        int[] state = new int[5];
        for (int y = 3; y < h - 3; y += 2) {
            state[0] = state[1] = state[2] = state[3] = state[4] = 0;
            int currentState = 0;
            int row = y * w;

            for (int x = 0; x < w; x++) {
                if (bin[row + x] != 0) {
                    if ((currentState & 1) == 1) currentState++;
                    state[currentState]++;
                } else {
                    if ((currentState & 1) == 0) {
                        if (currentState == 4) {
                            if (checkRatio(state)) {
                                int[] vState = new int[5];
                                float centerX = centerFromEnd(state, x);
                                if (crossCheckVertical(bin, w, h, (int) centerX, y, state[2], vState)) {
                                    float centerY = centerFromEnd(vState, y + vState[3] + vState[4]);
                                    float modSize = (state[0] + state[1] + state[2] + state[3] + state[4]) / 7.0f;
                                    
                                    boolean duplicate = false;
                                    for (int i = 0; i < fCount; i++) {
                                        if (Math.abs(fx[i] - centerX) < 10 && Math.abs(fy[i] - centerY) < 10) {
                                            duplicate = true; break;
                                        }
                                    }
                                    if (!duplicate && fCount < 16) {
                                        fx[fCount] = centerX; fy[fCount] = centerY; fmod[fCount] = modSize;
                                        fCount++;
                                    }
                                }
                                state[0] = state[2]; state[1] = state[3]; state[2] = state[4];
                                state[3] = 1; state[4] = 0;
                                currentState = 3;
                            } else {
                                state[0] = state[2]; state[1] = state[3]; state[2] = state[4];
                                state[3] = 1; state[4] = 0;
                                currentState = 3;
                            }
                        } else {
                            currentState++;
                            state[currentState]++;
                        }
                    } else {
                        state[currentState]++;
                    }
                }
            }
        }

        if (fCount >= 3) {
            float tlX = fx[0], tlY = fy[0];
            float trX = fx[1], trY = fy[1];
            float blX = fx[2], blY = fy[2];

            if (tlX > trX) { float tx = tlX; tlX = trX; trX = tx; float ty = tlY; tlY = trY; trY = ty; }
            if (tlY > blY) { float tx = tlX; tlX = blX; blX = tx; float ty = tlY; tlY = blY; blY = ty; }

            float modSize = (fmod[0] + fmod[1] + fmod[2]) / 3.0f;
            int dim = (int) (Math.sqrt((trX - tlX)*(trX - tlX) + (trY - tlY)*(trY - tlY)) / modSize) + 7;
            if (dim < 21) dim = 21;
            
            return "QR Code Found (Version ~" + ((dim - 17) / 4) + ")";
        }

        return null;
    }
}
