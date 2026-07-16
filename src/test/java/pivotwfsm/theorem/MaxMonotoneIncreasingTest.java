package pivotwfsm.theorem;

import pivotwfsm.weight.MaxAggregator;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MAX is monotone increasing - useful for "is there a strong component" but
 * cannot drive downward pruning. Recorded for completeness.
 */
class MaxMonotoneIncreasingTest {

    @Test
    void maxIsMonotoneIncreasing() {
        MaxAggregator max = new MaxAggregator();
        Random rng = new Random(7);

        for (int trial = 0; trial < 10_000; trial++) {
            double previous = rng.nextDouble();
            double newEdge = rng.nextDouble();
            double extended = max.extend(previous, newEdge);
            assertTrue(extended >= previous,
                "W_max must be non-decreasing: prev=" + previous
                    + " newEdge=" + newEdge + " extended=" + extended);
        }
    }
}
