package pivotwfsm.theorem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Documents (by counterexample) why AVG cannot be used as an anti-monotone
 * pruning measure: adding a heavier edge increases the average. This is the
 * exact gap that forces WFSM-MaxPWS / WeFreS to invent MaxPWS / MaxPosW.
 */
class AntiMonotoneAvgFalsifyTest {

    @Test
    void avgCanIncreaseUnderExtension() {
        // S has edges of weight {0.4, 0.5}: W_avg(S) = 0.45.
        double weightSum = 0.4 + 0.5;
        int edgeCount = 2;
        double avgBefore = weightSum / edgeCount;

        // Extend with a heavier edge 0.9.
        double newEdge = 0.9;
        double avgAfter = (weightSum + newEdge) / (edgeCount + 1);

        assertTrue(avgAfter > avgBefore,
            "Counterexample: W_avg(S')=" + avgAfter + " > W_avg(S)=" + avgBefore);
    }
}
