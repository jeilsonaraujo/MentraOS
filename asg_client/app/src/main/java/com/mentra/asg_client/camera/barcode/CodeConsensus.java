package com.mentra.asg_client.camera.barcode;

/**
 * Consensus + dedup guard for decoded barcode values (DIM-560).
 *
 * <p>Curved/awkward 1D codes make decoders occasionally return a WRONG but checksum-valid value — a
 * different wrong value each frame. A genuine code decodes to the SAME value repeatedly, so a value
 * is only CONFIRMED once it agrees across {@code consensusN} consecutive observations. A confirmed
 * value is then not re-confirmed while it keeps being seen inside the dedup window (the window
 * slides while the code sits in frame).
 *
 * <p>Pure state machine — no Android or ML Kit dependencies — extracted from
 * {@link BarcodeScanController} so the guard is unit-testable on the JVM. The controller owns
 * logging and result-sink delivery; this class only decides.
 */
final class CodeConsensus {

    private final int consensusN;
    private final long dedupWindowMs;

    private String pendingValue = null;
    private int pendingCount = 0;
    private String lastValue = null;
    private long lastValueAt = 0L;

    CodeConsensus(int consensusN, long dedupWindowMs) {
        this.consensusN = consensusN;
        this.dedupWindowMs = dedupWindowMs;
    }

    /**
     * Feed one decoded observation.
     *
     * @return true when this observation newly CONFIRMS the value (agreed across
     *     {@code consensusN} observations and not a recent duplicate); false while tentative, or
     *     when the value was already confirmed inside the dedup window.
     */
    boolean observe(String value, long nowMs) {
        if (value.equals(pendingValue)) {
            pendingCount++;
        } else {
            pendingValue = value;
            pendingCount = 1;
        }

        boolean recentlyConfirmed =
                value.equals(lastValue) && (nowMs - lastValueAt) < dedupWindowMs;
        if (pendingCount < consensusN || recentlyConfirmed) {
            if (recentlyConfirmed) {
                lastValueAt = nowMs; // the window slides while the code stays in frame
            }
            return false;
        }

        // Confirmed: agreed across consensusN observations.
        lastValue = value;
        lastValueAt = nowMs;
        pendingValue = null;
        pendingCount = 0;
        return true;
    }

    /** Forget all pending and confirmed state (a new scan session starts clean). */
    void reset() {
        pendingValue = null;
        pendingCount = 0;
        lastValue = null;
        lastValueAt = 0L;
    }
}
