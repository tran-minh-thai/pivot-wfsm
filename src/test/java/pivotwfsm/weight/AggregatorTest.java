package pivotwfsm.weight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AggregatorTest {

    private static final double EPS = 1e-12;

    @Test
    void minAggregateSingleEdge() {
        Aggregator m = new MinAggregator();
        assertEquals(0.42, m.aggregate(new double[]{0.42}), EPS);
    }

    @Test
    void minAggregateMultipleEdges() {
        Aggregator m = new MinAggregator();
        assertEquals(0.10, m.aggregate(new double[]{0.85, 0.10, 0.60}), EPS);
        assertEquals(0.50, m.aggregate(new double[]{0.50}), EPS);
        assertEquals(0.40, m.aggregate(new double[]{0.65, 0.80, 0.40}), EPS);
    }

    @Test
    void minIsAntiMonotone() {
        assertTrue(new MinAggregator().isAntiMonotone());
    }

    @Test
    void minExtendIsIdempotentWithAggregate() {
        Aggregator m = new MinAggregator();
        double[] weights = {0.85, 0.40, 0.70};
        double extendBased = m.aggregate(new double[]{weights[0], weights[1]});
        extendBased = m.extend(extendBased, weights[2]);
        double bulk = m.aggregate(weights);
        assertEquals(bulk, extendBased, EPS,
            "Incremental extend should equal bulk aggregate for MIN");
    }

    @Test
    void maxAggregate() {
        Aggregator m = new MaxAggregator();
        assertEquals(0.95, m.aggregate(new double[]{0.10, 0.95, 0.60}), EPS);
        assertFalse(m.isAntiMonotone());
    }

    @Test
    void avgAggregate() {
        Aggregator a = new AvgAggregator();
        assertEquals(0.525, a.aggregate(new double[]{0.10, 0.95}), EPS);
        assertEquals((0.65 + 0.80 + 0.40) / 3.0, a.aggregate(new double[]{0.65, 0.80, 0.40}), EPS);
        assertFalse(a.isAntiMonotone());
    }

    @Test
    void avgExtendThrows() {
        // AVG is not incrementally composable from (previous, newEdge) alone
        // - needs the edge count. Calling extend() should signal this clearly.
        Aggregator a = new AvgAggregator();
        assertThrows(UnsupportedOperationException.class, () -> a.extend(0.5, 0.7));
    }

    @Test
    void aggregateOfEmptyArrayThrows() {
        // 0-edge "patterns" are not meaningful; aggregator must refuse rather
        // than return NaN silently.
        assertThrows(IllegalArgumentException.class,
            () -> new MinAggregator().aggregate(new double[0]));
        assertThrows(IllegalArgumentException.class,
            () -> new MaxAggregator().aggregate(new double[0]));
        assertThrows(IllegalArgumentException.class,
            () -> new AvgAggregator().aggregate(new double[0]));
    }

    @Test
    void aggregatorNamesAreStableForCsvSchemas() {
        // Names are written into results CSVs; locking them down here means
        // a rename will surface as a failed test, not a silent CSV column shift.
        assertEquals("MIN", new MinAggregator().name());
        assertEquals("MAX", new MaxAggregator().name());
        assertEquals("AVG", new AvgAggregator().name());
    }
}
