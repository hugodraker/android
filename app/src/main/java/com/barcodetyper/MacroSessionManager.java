/* ============================================================================
 * Android Barcode Scanner
 *
 *  2026 HUGO
 *
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 * ============================================================================ */
package com.barcodetyper;

import java.util.HashMap;
import java.util.Map;

public class MacroSessionManager {
    
    private static class MacroSession {
        int expectedTotalSegments = -1;
        final Map<Integer, String> segments = new HashMap<>();
        
        boolean isComplete() {
            if (expectedTotalSegments == -1) return false;
            return segments.size() == expectedTotalSegments;
        }
        
        String compilePayload() {
            StringBuilder sb = new StringBuilder();
            // PDF417 Macro Segment Indices are 0-indexed in standard implementations
            for (int i = 0; i < expectedTotalSegments; i++) {
                sb.append(segments.get(i));
            }
            return sb.toString();
        }
    }

    private final Map<String, MacroSession> activeSessions = new HashMap<>();

    /**
     * Feeds a scanned result into the session manager.
     * @return The fully concatenated String if the scan completed a macro sequence,
     *         the standard string if it was a normal barcode, or null if still buffering.
     */
    public String processResult(PDF417Compaction.DecoderResult result) {
        if (result == null) return null;

        // If it's a standard, non-macro barcode, return it immediately.
        if (!result.isMacro()) {
            return result.payload;
        }

        // Fetch or initialize the macro session for this specific File ID
        MacroSession session = activeSessions.computeIfAbsent(result.fileId, k -> new MacroSession());
        
        // Store the segment
        session.segments.put(result.segmentIndex, result.payload);
        
        // If this result flagged the 922 terminator, we now know the total size of the file
        if (result.isLastSegment) {
            session.expectedTotalSegments = result.segmentIndex + 1;
        }

        // Check if all segments have been successfully scanned
        if (session.isComplete()) {
            String finalPayload = session.compilePayload();
            activeSessions.remove(result.fileId); // Clear cache once complete
            return finalPayload;
        }

        // Still waiting on more segments
        return null;
    }
}
