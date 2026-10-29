/* ============================================================================
 * Android Barcode Scanner
 *
 *  2026 HUGO
 *
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 * ============================================================================ */
package com.barcodetyper;

import java.util.Arrays;

public class PDF417ErrorCorrection {
    private static int gf929_mul(int a, int b) {
        return (int) (((long) ((a % 929 + 929) % 929) * ((b % 929 + 929) % 929)) % 929);
    }
    
    private static int gf929_pow(int base, int exp) {
        int res = 1;
        base = (base % 929 + 929) % 929;
        while (exp > 0) {
            if ((exp & 1) != 0) res = (res * base) % 929;
            base = (base * base) % 929;
            exp >>= 1;
        }
        return res;
    }

    private static boolean solveKnownLocations(int[] cw, int N, int K, int[] S, int[] errIdx, int E) {
        if (E <= 0 || E > K - 2 || E > 256) return false;
        int[] X = new int[256];
        for (int m = 0; m < E; m++) {
            if (errIdx[m] < 0 || errIdx[m] >= N) return false;
            X[m] = gf929_pow(3, N - 1 - errIdx[m]);
        }

        int[] trial = Arrays.copyOf(cw, N);
        for (int m = 0; m < E; m++) {
            int[] c = new int[257];
            c[0] = 1;
            int deg = 0;
            int denom = X[m];

            for (int r = 0; r < E; r++) {
                if (r == m) continue;
                int negXr = (929 - X[r]) % 929;
                for (int k = deg + 1; k >= 1; k--) c[k] = (c[k - 1] + gf929_mul(c[k], negXr)) % 929;
                c[0] = gf929_mul(c[0], negXr);
                deg++;
                denom = gf929_mul(denom, (X[m] + 929 - X[r]) % 929);
            }
            if (denom == 0) return false;

            int num = 0;
            for (int k = 0; k < E; k++) num = (num + gf929_mul(c[k], S[k + 1])) % 929;
            int errVal = gf929_mul(num, PDF417Data.pdfInv929[denom]);
            trial[errIdx[m]] = (trial[errIdx[m]] + 929 - errVal) % 929;
        }

        for (int j = 1; j <= K; j++) {
            int syn = 0, a_j = gf929_pow(3, j);
            for (int i = 0; i < N; i++) syn = (gf929_mul(syn, a_j) + trial[i]) % 929;
            if (syn != 0) return false;
        }

        System.arraycopy(trial, 0, cw, 0, N);
        return true;
    }

    public static boolean verifyAndCorrect(int[] cw, int payloadLen, int eccCount, int[] erasures, int numErasures) {
        int N = payloadLen + eccCount;
        if (payloadLen < 2 || eccCount < 2 || N > 928) return false;

        int maxErasures = (eccCount >= 8) ? (eccCount / 4) : ((eccCount == 4) ? 1 : 0);
        if (numErasures > maxErasures) return false;

        for (int i = 0; i < N; i++) if (cw[i] < 0 || cw[i] >= 929) cw[i] = 0;

        int[] S = new int[520];
        boolean hasErr = false;
        for (int j = 1; j <= eccCount; j++) {
            int syn = 0, a_j = gf929_pow(3, j);
            for (int i = 0; i < N; i++) syn = (gf929_mul(syn, a_j) + cw[i]) % 929;
            S[j] = syn;
            if (syn != 0) hasErr = true;
        }
        if (!hasErr) return cw[0] == payloadLen;
        if (eccCount < 4) return false;

        if (numErasures > 0 && numErasures <= maxErasures) {
            if (solveKnownLocations(cw, N, eccCount, S, erasures, numErasures)) return cw[0] == payloadLen;
            return false;
        }

        int K = eccCount; 
        int[] C = new int[514], B = new int[514];
        C[0] = 1; B[0] = 1;
        int L = 0, m = 1, b = 1;

        for (int n = 0; n < K; n++) {
            int d = S[n + 1];
            for (int i = 1; i <= L; i++) d = (d + gf929_mul(C[i], S[n + 1 - i])) % 929;
            if (d == 0) {
                m++;
            } else {
                int[] T = Arrays.copyOf(C, C.length);
                int coef = gf929_mul(d, PDF417Data.pdfInv929[b]);
                for (int i = m; i < 514; i++) C[i] = (C[i] + 929 - gf929_mul(coef, B[i - m])) % 929;
                if (2 * L <= n) {
                    L = n + 1 - L; B = T; b = d; m = 1;
                } else {
                    m++;
                }
            }
        }

        if (L <= 0 || 2 * L > K - 4) return false;

        int[] errIdx = new int[256];
        int errCnt = 0;
        for (int i = 0; i < N; i++) {
            int p = N - 1 - i;
            int xInv = PDF417Data.pdfInv929[gf929_pow(3, p)];
            int eval = 0, xPow = 1;
            for (int j = 0; j <= L; j++) {
                eval = (eval + gf929_mul(C[j], xPow)) % 929;
                xPow = gf929_mul(xPow, xInv);
            }
            if (eval == 0 && errCnt < 256) errIdx[errCnt++] = i;
        }

        if (errCnt != L) return false;
        if (!solveKnownLocations(cw, N, eccCount, S, errIdx, errCnt)) return false;
        return cw[0] == payloadLen;
    }
}
