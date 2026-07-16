package pivotwfsm.baselines.wfsm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the WFSM-MaxPWS canonical ordering (Lemma 1, paper §4.1):
 *   same edge position → heavier weight is the smaller tuple.
 *
 * <p>This inversion (vs. the natural double order) is the linchpin of the
 * tighter MaxPWS bound - if the ordering ever flips, FTW stops being the
 * heaviest possible extension and the bound silently becomes incorrect.
 */
class WfsmTupleAndCodeTest {

    // Labels for readability.
    private static final int A = 0, B = 1, C = 2;
    private static final int E = 0;

    @Test
    void lemma1HeavierWeightIsSmaller() {
        // Same edge position (0,1) and same labels (A,B): only weight differs.
        WfsmTuple light = new WfsmTuple(0, 1, A, B, E, 0.5);
        WfsmTuple heavy = new WfsmTuple(0, 1, A, B, E, 0.9);
        assertTrue(heavy.compareTo(light) < 0,
            "heavier weight must rank earlier per Lemma 1");
        assertTrue(light.compareTo(heavy) > 0);
    }

    @Test
    void weightTakesPrecedenceOverLabelLex() {
        // Same edge position. A-C with low weight vs. A-B with high weight.
        // gSpan would say (A,B) < (A,C) by label; WFSM says heavy beats light.
        WfsmTuple heavyAC = new WfsmTuple(0, 1, A, C, E, 0.9);
        WfsmTuple lightAB = new WfsmTuple(0, 1, A, B, E, 0.5);
        assertTrue(heavyAC.compareTo(lightAB) < 0,
            "weight ordering must outrank label-lex ordering");
    }

    @Test
    void labelLexOnlyMattersWhenWeightAndPositionEqual() {
        WfsmTuple ab = new WfsmTuple(0, 1, A, B, E, 0.7);
        WfsmTuple ac = new WfsmTuple(0, 1, A, C, E, 0.7);
        assertTrue(ab.compareTo(ac) < 0, "label lex breaks ties under equal weight");
    }

    @Test
    void edgePositionDominatesEverything() {
        // Forward (0,1) vs forward (1,2): smaller `to` first regardless of weight.
        WfsmTuple zeroOneLight = new WfsmTuple(0, 1, A, B, E, 0.1);
        WfsmTuple oneTwoHeavy  = new WfsmTuple(1, 2, B, C, E, 0.99);
        assertTrue(zeroOneLight.compareTo(oneTwoHeavy) < 0,
            "edge position must outrank weight");
    }

    @Test
    void emptyCodeHasZeroFtwAndZeroAvg() {
        WfsmCode empty = WfsmCode.empty();
        assertEquals(0.0, empty.firstTupleWeight());
        assertEquals(0.0, empty.averageWeight());
    }

    @Test
    void singleEdgeCodeFtwAndAvgEqualEdgeWeight() {
        WfsmCode code = WfsmCode.of(new WfsmTuple(0, 1, A, B, E, 0.85));
        assertEquals(0.85, code.firstTupleWeight(), 1e-12);
        assertEquals(0.85, code.averageWeight(), 1e-12);
    }

    @Test
    void twoEdgeCodeAveragesWeights() {
        // Matches the paper's gx example (§4.4): edges weighted 1.6 and 0.75.
        WfsmCode gx = WfsmCode.of(
            new WfsmTuple(0, 1, A, B, E, 1.6),
            new WfsmTuple(0, 2, A, C, E, 0.75)
        );
        assertEquals(1.6,  gx.firstTupleWeight(), 1e-12);
        assertEquals((1.6 + 0.75) / 2.0, gx.averageWeight(), 1e-12);
        assertEquals(1.175, gx.averageWeight(), 1e-12);
    }

    @Test
    void rejectsFirstEdgeNotZeroOne() {
        assertThrows(IllegalArgumentException.class,
            () -> WfsmCode.of(new WfsmTuple(0, 5, A, B, E, 1.0)));
    }

    @Test
    void rejectsSelfLoop() {
        assertThrows(IllegalArgumentException.class,
            () -> WfsmCode.of(new WfsmTuple(0, 0, A, A, E, 1.0)));
    }

    @Test
    void wfsmCanonicalDiffersFromGSpanWhenWeightsDiffer() {
        // For label-pair (A,B): gSpan canonical first edge is always
        // (0,1,A,_,B). WFSM canonical first edge is the heaviest A-B in the
        // pattern (by Lemma 1). When all A-B edges have equal weight they agree;
        // when weights differ they disagree on which is "smallest".
        WfsmTuple t1 = new WfsmTuple(0, 1, A, B, E, 0.4);  // lighter
        WfsmTuple t2 = new WfsmTuple(0, 1, A, B, E, 0.9);  // heavier
        // gSpan would compare them as equal (same labels); WFSM ranks t2 first.
        assertTrue(t2.compareTo(t1) < 0);
    }
}
