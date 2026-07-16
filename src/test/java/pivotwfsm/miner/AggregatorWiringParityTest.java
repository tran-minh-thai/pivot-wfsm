package pivotwfsm.miner;

import pivotwfsm.core.MultiGraphDB;
import pivotwfsm.core.Pattern;
import pivotwfsm.weight.AvgAggregator;
import pivotwfsm.weight.MinAggregator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Aggregator-based mine() must give the same pattern set as the
 * enum-based mine() for the corresponding aggregator. This is a parity test:
 * the enum facade is just a convenience wrapper around the Aggregator path.
 * If they ever drift, downstream ablation experiments will silently diverge.
 */
class AggregatorWiringParityTest {

    private static MultiGraphDB db;

    @BeforeAll
    static void load() throws IOException {
        db = MultiGraphDB.loadJson(Paths.get("data/sample/illustrative.json"));
    }

    @Test
    void minAggregatorParityWithEnumMin_atSigma3() {
        List<Pattern> viaEnum = EmbeddingStoreMiner.mine(db, 3, 0.5, EmbeddingStoreMiner.AggregatorMode.MIN);
        List<Pattern> viaAgg  = EmbeddingStoreMiner.mine(db, 3, 0.5, new MinAggregator());
        assertPatternSetsEqual(viaEnum, viaAgg);
    }

    @Test
    void avgAggregatorParityWithEnumAvg_atSigma3() {
        List<Pattern> viaEnum = EmbeddingStoreMiner.mine(db, 3, 0.5, EmbeddingStoreMiner.AggregatorMode.AVG);
        List<Pattern> viaAgg  = EmbeddingStoreMiner.mine(db, 3, 0.5, new AvgAggregator());
        assertPatternSetsEqual(viaEnum, viaAgg);
    }

    @Test
    void minAggregatorParityAtSigma6() {
        // The configuration where MIN and AVG disagree.
        List<Pattern> viaEnum = EmbeddingStoreMiner.mine(db, 6, 0.5, EmbeddingStoreMiner.AggregatorMode.MIN);
        List<Pattern> viaAgg  = EmbeddingStoreMiner.mine(db, 6, 0.5, new MinAggregator());
        assertPatternSetsEqual(viaEnum, viaAgg);
    }

    @Test
    void avgAggregatorParityAtSigma6() {
        List<Pattern> viaEnum = EmbeddingStoreMiner.mine(db, 6, 0.5, EmbeddingStoreMiner.AggregatorMode.AVG);
        List<Pattern> viaAgg  = EmbeddingStoreMiner.mine(db, 6, 0.5, new AvgAggregator());
        assertPatternSetsEqual(viaEnum, viaAgg);
    }

    @Test
    void minIsTighterThanAvgViaAggregatorPath() {
        // Anti-monotone bite: MIN prunes whole subtrees that AVG must keep open.
        // |MIN| <= |AVG| at the same threshold; verified along the Aggregator path.
        List<Pattern> resMin = EmbeddingStoreMiner.mine(db, 3, 0.5, new MinAggregator());
        List<Pattern> resAvg = EmbeddingStoreMiner.mine(db, 3, 0.5, new AvgAggregator());
        assertTrue(resMin.size() <= resAvg.size(),
            "MIN's frequent set should be a subset of AVG's at the same threshold");
    }

    private static void assertPatternSetsEqual(List<Pattern> a, List<Pattern> b) {
        Set<String> codesA = new HashSet<>();
        for (Pattern p : a) codesA.add(p.code().toString());
        Set<String> codesB = new HashSet<>();
        for (Pattern p : b) codesB.add(p.code().toString());
        assertEquals(codesA, codesB,
            "Enum-based and Aggregator-based mine() must return the same pattern set");
    }
}
