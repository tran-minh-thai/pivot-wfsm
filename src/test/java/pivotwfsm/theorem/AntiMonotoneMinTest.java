package pivotwfsm.theorem;

import pivotwfsm.weight.MinAggregator;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property-based check that W_min is anti-monotone under any edge extension.
 * Backs the central theoretical claim of the paper.
 */
class AntiMonotoneMinTest {

    @Test
    void minIsAntiMonotoneUnderExtension() {
        MinAggregator min = new MinAggregator();
        Random rng = new Random(42);

        for (int trial = 0; trial < 10_000; trial++) {
            double previous = rng.nextDouble();
            double newEdge = rng.nextDouble();
            double extended = min.extend(previous, newEdge);
            assertTrue(extended <= previous,
                "W_min must be non-increasing: prev=" + previous
                    + " newEdge=" + newEdge + " extended=" + extended);
        }
    }
}
