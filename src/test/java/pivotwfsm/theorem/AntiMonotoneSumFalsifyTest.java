package pivotwfsm.theorem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SUM is monotone INCREASING for non-negative weights - the opposite of what
 * downward-closure pruning needs. Recorded here for completeness.
 */
class AntiMonotoneSumFalsifyTest {

    @Test
    void sumIsMonotoneIncreasing() {
        double sumBefore = 0.4 + 0.5;
        double sumAfter = sumBefore + 0.3;
        assertTrue(sumAfter >= sumBefore,
            "W_sum must be non-decreasing for non-negative weights");
    }
}
