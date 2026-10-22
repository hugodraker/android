/* ============================================================================
 * Android Barcode Typer
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 * ============================================================================ */
package com.barcodetyper;

public class UnifiedScanner {
    public static String scan(byte[] yPlane, int w, int h, int rowStride) {
        
        // 1. Sweep for 1D Barcodes (Highest priority)
        String oneD = OneDScanner.scanBuffer(yPlane, w, h, rowStride);
        if (oneD != null) return oneD;

        // 2. Sweep for PDF417 Codes
        String pdf = PDF417Scanner.scanBuffer(yPlane, w, h, rowStride);
        if (pdf != null) return pdf;

        // 3. Rotate Y-Plane 90 degrees to cross-check perpendicular barcodes
        byte[] rotated = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int srcRow = y * rowStride;
            for (int x = 0; x < w; x++) {
                rotated[x * h + (h - 1 - y)] = yPlane[srcRow + x];
            }
        }

        // 4. Sweep Rotated Buffer for 1D and PDF417
        oneD = OneDScanner.scanBuffer(rotated, h, w, h);
        if (oneD != null) return oneD;

        pdf = PDF417Scanner.scanBuffer(rotated, h, w, h);
        if (pdf != null) return pdf;

        // 5. Sweep for DataMatrix (Internally rotation invariant)
        String dm = DataMatrixScanner.scanBuffer(yPlane, w, h, rowStride);
        if (dm != null) return dm;
        
        // 6. Sweep for QR Codes LAST
        // (The stub's loose finder logic causes false positives on dense 1D codes)
        return QRScanner.scanBuffer(yPlane, w, h, rowStride);
    }
}