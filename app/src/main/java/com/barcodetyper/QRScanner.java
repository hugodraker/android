/* ============================================================================
 * Android Barcode Typer
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 * ============================================================================ */
package com.barcodetyper;

public class QRScanner {private static final int[] expTable = new int[512];
    private static final int[] logTable = new int[256];
    private static boolean gfInit = false;

    private static synchronized void initGF256() {
        if (gfInit) return;
        int x = 1;
        for (int i = 0; i < 255; i++) {
            expTable[i] = expTable[i + 255] = x;
            logTable[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D; // QR Code GF(256) polynomial 285
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

    private static boolean correctErrors(int[] cw, int dataCw, int eccCw) {
        initGF256();
        int N = dataCw + eccCw;
        int[] S = new int[128];
        boolean hasErr = false;

        for (int j = 0; j < eccCw; j++) {
            int syn = 0;
            int aJ = expTable[j];
            for (int i = 0; i < N; i++) syn = gfMul(syn, aJ) ^ cw[i];
            S[j] = syn;
            if (syn != 0) hasErr = true;
        }
        if (!hasErr) return true;

        int[] C = new int[128], B = new int[128];
        C[0] = 1; B[0] = 1;
        int L = 0, m = 1, b = 1;

        for (int n = 0; n < eccCw; n++) {
            int d = S[n];
            for (int i = 1; i <= L; i++) d ^= gfMul(C[i], S[n - i]);
            if (d == 0) {
                m++;
            } else if (2 * L <= n) {
                int[] T = java.util.Arrays.copyOf(C, 128);
                int coef = gfMul(d, gfInv(b));
                for (int i = m; i < 128; i++) C[i] ^= gfMul(coef, B[i - m]);
                L = n + 1 - L;
                System.arraycopy(T, 0, B, 0, 128);
                b = d;
                m = 1;
            } else {
                int coef = gfMul(d, gfInv(b));
                for (int i = m; i < 128; i++) C[i] ^= gfMul(coef, B[i - m]);
                m++;
            }
        }
        if (L > eccCw / 2 || L > 16) return false;

        int[] errPos = new int[32];
        int errCnt = 0;
        for (int i = 0; i < N; i++) {
            int p = N - 1 - i;
            int xInv = expTable[(255 - (p % 255)) % 255], eval = 0, xPow = 1;
            for (int j = 0; j <= L; j++) {
                eval ^= gfMul(C[j], xPow);
                xPow = gfMul(xInv, xPow);
            }
            if (eval == 0 && errCnt < 32) errPos[errCnt++] = i;
        }
        if (errCnt != L) return false;

        int[] omega = new int[128];
        for (int i = 0; i < eccCw; i++) {
            for (int j = 0; j <= L && i + j < eccCw; j++) {
                omega[i + j] ^= gfMul(S[i], C[j]);
            }
        }

        for (int e = 0; e < errCnt; e++) {
            int idx = errPos[e], p = N - 1 - idx;
            int xInv = expTable[(255 - (p % 255)) % 255], num = 0, xPow = 1;
            for (int j = 0; j < eccCw; j++) {
                num ^= gfMul(omega[j], xPow);
                xPow = gfMul(xPow, xInv);
            }
            int den = 0, xInv2 = gfMul(xInv, xInv);
            xPow = 1;
            for (int j = 1; j <= L; j += 2) {
                den ^= gfMul(C[j], xPow);
                xPow = gfMul(xPow, xInv2);
            }
            if (den == 0) return false;
            cw[idx] ^= gfMul(num, gfInv(den));
        }
        return true;
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

    private static int sampleGrid(byte[] bin, int w, int h, float tlX, float tlY, float trX, float trY, float blX, float blY, int dim, byte[] grid) {
        float xStepTR = (trX - tlX) / (dim - 7);
        float yStepTR = (trY - tlY) / (dim - 7);
        float xStepBL = (blX - tlX) / (dim - 7);
        float yStepBL = (blY - tlY) / (dim - 7);

        for (int y = 0; y < dim; y++) {
            float rowStartX = tlX + xStepBL * (y - 3.5f);
            float rowStartY = tlY + yStepBL * (y - 3.5f);
            for (int x = 0; x < dim; x++) {
                float px = rowStartX + xStepTR * (x - 3.5f);
                float py = rowStartY + yStepTR * (x - 3.5f);
                int ix = (int) px, iy = (int) py;
                if (ix >= 0 && ix < w && iy >= 0 && iy < h) {
                    grid[y * dim + x] = bin[iy * w + ix];
                } else {
                    grid[y * dim + x] = 0;
                }
            }
        }
        return 1;
    }

    private static boolean applyMask(int mask, int x, int y) {
        switch (mask) {
            case 0: return (x + y) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (x + y) % 3 == 0;
            case 4: return (y / 2 + x / 3) % 2 == 0;
            case 5: return (x * y) % 2 + (x * y) % 3 == 0;
            case 6: return ((x * y) % 2 + (x * y) % 3) % 2 == 0;
            case 7: return ((x + y) % 2 + (x * y) % 3) % 2 == 0;
        }
        return false;
    }

    public static String scanBuffer(byte[] yPlane, int w, int h, int rowStride) {
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

    float[] fx = new float[16], fy = new float[16], fmod = new float[16];
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
                            state[3] = 1; state[4] = 0; currentState = 3;
                        } else {
                            state[0] = state[2]; state[1] = state[3]; state[2] = state[4];
                            state[3] = 1; state[4] = 0; currentState = 3;
                        }
                    } else {
                        currentState++; state[currentState]++;
                    }
                } else {
                    state[currentState]++;
                }
            }
        }
    }

    if (fCount >= 3) {
        float tlX = fx[0], tlY = fy[0], trX = fx[1], trY = fy[1], blX = fx[2], blY = fy[2];
        if (tlX > trX) { float tx = tlX; tlX = trX; trX = tx; float ty = tlY; tlY = trY; trY = ty; }
        if (tlY > blY) { float tx = tlX; tlX = blX; blX = tx; float ty = tlY; tlY = blY; blY = ty; }

        float modSize = (fmod[0] + fmod[1] + fmod[2]) / 3.0f;
        int dim = (int) (Math.sqrt((trX - tlX)*(trX - tlX) + (trY - tlY)*(trY - tlY)) / modSize) + 7;
        if (dim < 21) dim = 21;
        
        dim = 21 + 4 * ((dim - 19) / 4);
        if (dim > 57) return null;

        byte[] grid = new byte[dim * dim];
        sampleGrid(bin, w, h, tlX, tlY, trX, trY, blX, blY, dim, grid);

        int formatInfo = 0;
        for (int i = 0; i < 6; i++) formatInfo = (formatInfo << 1) | grid[8 * dim + i];
        formatInfo = (formatInfo << 1) | grid[8 * dim + 7];
        formatInfo = (formatInfo << 1) | grid[8 * dim + 8];
        formatInfo = (formatInfo << 1) | grid[7 * dim + 8];
        for (int i = 5; i >= 0; i--) formatInfo = (formatInfo << 1) | grid[i * dim + 8];
        formatInfo ^= 0x5412;
        
        int mask = formatInfo & 0x07;
        
        // Expanded to 512 for V6-V10 support
        byte[] rawBytes = new byte[512];
        int bitCount = 0, byteIdx = 0, currentByte = 0;
        boolean upward = true;

        for (int x = dim - 1; x > 0; x -= 2) {
            if (x == 6) x--;
            for (int y = 0; y < dim; y++) {
                int r = upward ? dim - 1 - y : y;
                for (int col = 0; col < 2; col++) {
                    int c = x - col;
                    if ((r < 9 && c < 9) || (r < 9 && c >= dim - 8) || (r >= dim - 8 && c < 9) || r == 6 || c == 6) continue;
                    
                    int bit = grid[r * dim + c];
                    if (applyMask(mask, c, r)) bit ^= 1;
                    
                    currentByte = (currentByte << 1) | bit;
                    bitCount++;
                    if (bitCount == 8) {
                        if (byteIdx < rawBytes.length) rawBytes[byteIdx++] = (byte) currentByte;
                        bitCount = 0; currentByte = 0;
                    }
                }
            }
            upward = !upward;
        }

        int eccLevel = (formatInfo >> 3) & 0x03;
        int eccIdx = (eccLevel == 1) ? 0 : (eccLevel == 0) ? 1 : (eccLevel == 3) ? 2 : 3;
        int version = (dim - 17) / 4;
        
        int dataBytes = byteIdx / 2;
        int eccBytes = byteIdx - dataBytes;

        if (version >= 1 && version <= 10) {
            int[][] totalECCPerVersion = {
                {7, 10, 13, 17}, {10, 16, 22, 28}, {15, 26, 36, 44}, {20, 36, 52, 64}, 
                {26, 48, 72, 88}, {36, 64, 96, 112}, {40, 72, 108, 130}, {48, 88, 132, 156}, 
                {60, 110, 160, 192}, {72, 130, 192, 224}
            };
            eccBytes = totalECCPerVersion[version - 1][eccIdx];
            dataBytes = byteIdx - eccBytes;
            if (dataBytes < 0) dataBytes = byteIdx / 2;
        }
        
        int[] rsBlock = new int[byteIdx];
        for(int i=0; i<byteIdx; i++) rsBlock[i] = rawBytes[i] & 0xFF;
        
        if (correctErrors(rsBlock, dataBytes, eccBytes)) {
            StringBuilder result = new StringBuilder();
            int bitStream = 0, bitsAvailable = 0, readIdx = 0;
            
            while (readIdx < dataBytes) {
                while (bitsAvailable < 8 && readIdx < dataBytes) {
                    bitStream = (bitStream << 8) | rsBlock[readIdx++];
                    bitsAvailable += 8;
                }
                int mode = (bitStream >> (bitsAvailable - 4)) & 0x0F;
                bitsAvailable -= 4;
                if (mode == 0) break;
                
                if (mode == 4) { 
                    while (bitsAvailable < 8 && readIdx < dataBytes) {
                        bitStream = (bitStream << 8) | rsBlock[readIdx++];
                        bitsAvailable += 8;
                    }
                    int length = (bitStream >> (bitsAvailable - 8)) & 0xFF;
                    bitsAvailable -= 8;
                    
                    for (int i = 0; i < length && readIdx <= dataBytes; i++) {
                        while (bitsAvailable < 8 && readIdx < dataBytes) {
                            bitStream = (bitStream << 8) | rsBlock[readIdx++];
                            bitsAvailable += 8;
                        }
                        result.append((char) ((bitStream >> (bitsAvailable - 8)) & 0xFF));
                        bitsAvailable -= 8;
                    }
                } else if (mode == 2) { 
                    while (bitsAvailable < 9 && readIdx < dataBytes) {
                        bitStream = (bitStream << 8) | rsBlock[readIdx++];
                        bitsAvailable += 8;
                    }
                    int length = (bitStream >> (bitsAvailable - 9)) & 0x1FF;
                    bitsAvailable -= 9;
                } else {
                    break;
                }
            }
            if (result.length() > 0) return result.toString();
        }
    }
    return null;
}
}