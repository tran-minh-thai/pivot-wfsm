package pivotwfsm.pivot;

import pivotwfsm.core.MultiGraphDB;
import pivotwfsm.core.Pattern;
import pivotwfsm.miner.EmbeddingStoreMiner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Head-to-head correctness: the pivot miner (on-demand re-matching) and
 * the embedding-store baseline must emit EXACTLY the same pattern set under the
 * shared bottleneck-MIN measure. This is the apples-to-apples check that makes
 * the runtime comparison meaningful - same core, same F1, same canonical, same
 * prefilter; only the matching strategy differs.
 */
class PivotParityWithEmbeddingStoreTest {

    private static MultiGraphDB db;

    @BeforeAll
    static void load() throws Exception {
        db = MultiGraphDB.loadJson(Path.of("data/sample/illustrative.json"));
    }

    private static Set<String> codesOf(List<Pattern> patterns) {
        return patterns.stream().map(p -> p.code().toString())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> pivotCodesOf(PivotWfsmMiner.Result result) {
        return result.patterns().stream().map(p -> p.code().toString())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private void assertParity(int sigma, double tau) {
        Set<String> embed = codesOf(EmbeddingStoreMiner.mine(db, sigma, tau, EmbeddingStoreMiner.AggregatorMode.MIN));
        Set<String> pivot = pivotCodesOf(PivotWfsmMiner.mine(db, sigma, tau));
        assertEquals(embed, pivot,
            "pivot and embedding-store pattern sets differ at sigma=" + sigma + " tau=" + tau);
    }

    @Test
    void parityAcrossThresholdGrid() {
        assertParity(1, 0.0);
        assertParity(1, 0.5);
        assertParity(2, 0.5);
        assertParity(3, 0.5);
        assertParity(5, 0.5);
        assertParity(6, 0.5);
        assertParity(1, 0.7);
        assertParity(2, 0.7);
    }

    @Test
    void prefilterOnOffAgree() {
        Set<String> on = pivotCodesOf(PivotWfsmMiner.mine(
            db, 2, 0.5, PivotWfsmMiner.PrefilterMode.ON, PivotWfsmMiner.Matching.PIVOT));
        Set<String> off = pivotCodesOf(PivotWfsmMiner.mine(
            db, 2, 0.5, PivotWfsmMiner.PrefilterMode.OFF, PivotWfsmMiner.Matching.PIVOT));
        assertEquals(on, off, "prefilter must not change the result set");
    }

    @Test
    void pivotOnOffAgree() {
        Set<String> pivot = pivotCodesOf(PivotWfsmMiner.mine(
            db, 2, 0.5, PivotWfsmMiner.PrefilterMode.ON, PivotWfsmMiner.Matching.PIVOT));
        Set<String> plain = pivotCodesOf(PivotWfsmMiner.mine(
            db, 2, 0.5, PivotWfsmMiner.PrefilterMode.ON, PivotWfsmMiner.Matching.PLAIN));
        assertEquals(pivot, plain, "pivot-on and pivot-off must produce identical results");
    }
}
