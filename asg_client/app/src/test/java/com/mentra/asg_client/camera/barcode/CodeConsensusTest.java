package com.mentra.asg_client.camera.barcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * The consensus + dedup guard behind on-glasses barcode decoding.
 *
 * <p>The guard exists because curved 1D codes occasionally decode to a WRONG but checksum-valid
 * value — a different wrong value each frame — while a genuine code repeats. These tests pin the
 * confirm/dedup contract the sweep relies on.
 */
public class CodeConsensusTest {

    private static final int CONSENSUS_N = 2;
    private static final long WINDOW_MS = 3000L;

    private CodeConsensus guard() {
        return new CodeConsensus(CONSENSUS_N, WINDOW_MS);
    }

    @Test
    public void aSingleReadIsTentative_neverConfirmed() {
        CodeConsensus g = guard();

        assertThat(g.observe("4006381333931", 0)).isFalse();
    }

    @Test
    public void theSameValueOnTwoFramesConfirms() {
        CodeConsensus g = guard();

        assertThat(g.observe("4006381333931", 0)).isFalse();
        assertThat(g.observe("4006381333931", 100)).isTrue();
    }

    @Test
    public void alternatingMisreadsNeverConfirm() {
        // The misread case the guard exists for: a curved code returning a different
        // checksum-valid value each frame must never be reported.
        CodeConsensus g = guard();

        assertThat(g.observe("1111111111111", 0)).isFalse();
        assertThat(g.observe("2222222222222", 100)).isFalse();
        assertThat(g.observe("1111111111111", 200)).isFalse();
        assertThat(g.observe("2222222222222", 300)).isFalse();
    }

    @Test
    public void aConfirmedCodeSittingInFrameIsNotReReported() {
        CodeConsensus g = guard();
        g.observe("CODE-A", 0);
        assertThat(g.observe("CODE-A", 100)).isTrue(); // confirmed once

        // Still in frame, well inside the window — stays silent.
        assertThat(g.observe("CODE-A", 500)).isFalse();
        assertThat(g.observe("CODE-A", 1000)).isFalse();
    }

    @Test
    public void theDedupWindowSlidesWhileTheCodeStaysInFrame() {
        // Each sighting refreshes the window: a code held in frame past the nominal
        // window still does not re-report, because the window slid with it.
        CodeConsensus g = guard();
        g.observe("CODE-A", 0);
        g.observe("CODE-A", 100); // confirmed at t=100

        assertThat(g.observe("CODE-A", 2500)).isFalse(); // window slid to 2500
        assertThat(g.observe("CODE-A", 5000)).isFalse(); // 5000 - 2500 < 3000 → slid again
        assertThat(g.observe("CODE-A", 7900)).isFalse(); // still sliding
    }

    @Test
    public void theSameCodeReConfirmsAfterLeavingTheFrameLongEnough() {
        CodeConsensus g = guard();
        g.observe("CODE-A", 0);
        g.observe("CODE-A", 100); // confirmed at t=100

        // Gone for longer than the window, then seen twice again → a fresh scan.
        assertThat(g.observe("CODE-A", 4000)).isFalse(); // tentative again (count reset on confirm)
        assertThat(g.observe("CODE-A", 4100)).isTrue();
    }

    @Test
    public void aDifferentCodeConfirmsIndependentlyOfTheLastOne() {
        CodeConsensus g = guard();
        g.observe("CODE-A", 0);
        g.observe("CODE-A", 100); // CODE-A confirmed

        // A second, different code right after must earn its own consensus — and get it.
        assertThat(g.observe("CODE-B", 200)).isFalse();
        assertThat(g.observe("CODE-B", 300)).isTrue();
    }
}
