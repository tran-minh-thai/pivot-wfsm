package pivotwfsm.baselines.wfsm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies MaxPWS computation against the paper's worked simulation
 * (Section 4.4, subgraph gx in Figures 3 and 8).
 *
 * <p>If these constants drift, the bound is reproducing a different formula
 * than the paper - a correctness regression that would silently bias every
 * WFSM-MaxPWS runtime claim.
 */
class MaxPwsBoundTest {

    // From paper §4.4 setup:
    private static final double W_GX  = 1.175;   // (1.6 + 0.75) / 2
    private static final int    G_SZ  = 2;
    private static final double FTW   = 1.6;

    @Test
    void pwsAt4Edges_With3Partitions_Is_4_1625() {
        // PWS(gx_4) = (2 * 1.175 + (4-2) * 1.6) / 4 * 3 = (5.55 / 4) * 3 = 4.1625
        double v = MaxPwsBound.pwsAt(G_SZ, W_GX, FTW, /*m=*/4, /*mpf=*/3);
        assertEquals(4.1625, v, 1e-4);
    }

    @Test
    void pwsAt6Edges_With2Partitions_Is_2_9166() {
        // PWS(gx_6) = (2 * 1.175 + 4 * 1.6) / 6 * 2 = (8.75 / 6) * 2 ≈ 2.9166
        double v = MaxPwsBound.pwsAt(G_SZ, W_GX, FTW, /*m=*/6, /*mpf=*/2);
        assertEquals(2.91667, v, 1e-4);
    }

    @Test
    void pwsAt7Edges_With1Partition_Is_1_4786() {
        // PWS(gx_7) = (2 * 1.175 + 5 * 1.6) / 7 * 1 = (10.35 / 7) ≈ 1.4786
        double v = MaxPwsBound.pwsAt(G_SZ, W_GX, FTW, /*m=*/7, /*mpf=*/1);
        assertEquals(1.47857, v, 1e-4);
    }

    @Test
    void maxPwsOverAllPartitionsIs_4_1625() {
        // MaxPWS(gx) = max(4.1625, 2.9166, 1.4786) = 4.1625
        double v = MaxPwsBound.maxPwsOver(
            G_SZ, W_GX, FTW,
            new int[]{4, 6, 7},
            new int[]{3, 2, 1}
        );
        assertEquals(4.1625, v, 1e-4);
    }

    @Test
    void mPwsBeatsMaxW_OnPaperExample() {
        // Paper notes MaxPWS(gx) = 4.1625 is MUCH tighter than MaxW
        // (which would give MaxW * frequency = 2.0 * 3 = 6).
        double maxPws = MaxPwsBound.maxPwsOver(
            G_SZ, W_GX, FTW,
            new int[]{4, 6, 7},
            new int[]{3, 2, 1}
        );
        double naiveMaxW = 2.0 * 3;
        assertTrue(maxPws < naiveMaxW,
            "MaxPWS=" + maxPws + " should be strictly less than naive MaxW*sup="
                + naiveMaxW + " - that's the whole point of the bound");
    }

    @Test
    void zeroMpfYieldsZero() {
        assertEquals(0.0, MaxPwsBound.pwsAt(G_SZ, W_GX, FTW, 10, 0));
    }

    @Test
    void rejectsExtensionToFewerEdgesThanCurrent() {
        // Extending up to m < |g| is incoherent (we'd be removing edges).
        assertThrows(IllegalArgumentException.class,
            () -> MaxPwsBound.pwsAt(G_SZ, W_GX, FTW, /*m=*/1, /*mpf=*/1));
    }
}
