/* ============================================================================
 * Android Barcode Typer
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 * ============================================================================ */
package com.barcodetyper;

public class UnifiedScanner {
    public static String scan(byte[] yPlane, int w, int h, int rowStride) {
        
        // 1. Sweep standard hardware orientation (Landscape)
        String qr = QRScanner.scanBuffer(yPlane, w, h, rowStride);
        if (qr != null) return qr;

        String oneD = OneDScanner.scanBuffer(yPlane, w, h, rowStride);
        if (oneD != null) return oneD;

        String pdf = PDF417Scanner.scanBuffer(yPlane, w, h, rowStride);
        if (pdf != null) return pdf;

        // 2. Rotate the Y-Plane 90 degrees to cross-check for Portrait orientation
        byte[] rotated = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int srcRow = y * rowStride;
            for (int x = 0; x < w; x++) {
                // 90-degree clockwise rotation mapping
                rotated[x * h + (h - 1 - y)] = yPlane[srcRow + x];
            }
        }

        // Sweep again on the rotated buffer (Width and Height are now swapped)
        qr = QRScanner.scanBuffer(rotated, h, w, h);
        if (qr != null) return qr;

        oneD = OneDScanner.scanBuffer(rotated, h, w, h);
        if (oneD != null) return oneD;

        pdf = PDF417Scanner.scanBuffer(rotated, h, w, h);
        if (pdf != null) return pdf;

        // 3. DataMatrix sweeps last on the original buffer. 
        // It is internally rotation-invariant, so it only needs to be called once.
        return DataMatrixScanner.scanBuffer(yPlane, w, h, rowStride);
    }
}
