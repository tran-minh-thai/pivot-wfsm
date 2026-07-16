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
 * <b>Correctness invariant for the unified prefilter:</b> turning the
 * prefilter on or off must NOT change the set of frequent patterns. The
 * prefilter is a pure pruning optimization - anything it skips would have
 * been pruned later anyway.
 *
 * <p>If this test ever fails, the prefilter's upper-bound logic is too
 * aggressive (kills a label pair that should survive). That would be a
 * correctness regression, not just a performance regression.
 *
 * <p>The companion test verifies that the prefilter actually <em>does</em>
 * skip something on the illustrative dataset - otherwise it would be doing
 * no work and producing trivial parity.
 */
class PrefilterParityTest {

    private static MultiGraphDB db;
    private static ExtensionLookupTable prefilter;

    @BeforeAll
    static void load() throws IOException {
        db = MultiGraphDB.loadJson(Paths.get("data/sample/illustrative.json"));
        prefilter = ExtensionLookupTable.build(db);
    }

    @Test
    void minPrefilterParityAtSigma3() {
        List<Pattern> noPref   = EmbeddingStoreMiner.mine(db, 3, 0.5, new MinAggregator());
        List<Pattern> withPref = EmbeddingStoreMiner.mine(db, 3, 0.5, new MinAggregator(), prefilter, null);
        assertSamePatternSet(noPref, withPref);
    }

    @Test
    void minPrefilterParityAtSigma6() {
        List<Pattern> noPref   = EmbeddingStoreMiner.mine(db, 6, 0.5, new MinAggregator());
        List<Pattern> withPref = EmbeddingStoreMiner.mine(db, 6, 0.5, new MinAggregator(), prefilter, null);
        assertSamePatternSet(noPref, withPref);
    }

    @Test
    void avgPrefilterParityAtSigma6() {
        // The measure-divergence case. Prefilter must NOT silently kill the path A-B-C since
        // it's the symmetric-difference pattern; AVG must still surface it.
        List<Pattern> noPref   = EmbeddingStoreMiner.mine(db, 6, 0.5, new AvgAggregator());
        List<Pattern> withPref = EmbeddingStoreMiner.mine(db, 6, 0.5, new AvgAggregator(), prefilter, null);
        assertSamePatternSet(noPref, withPref);
    }

    @Test
    void mineWithPrefilterFacadeMatchesExplicit() {
        // The mineWithPrefilter() convenience builds the table internally;
        // its output must match the explicit-prefilter call byte-for-byte.
        List<Pattern> viaFacade   = EmbeddingStoreMiner.mineWithPrefilter(db, 3, 0.5, new MinAggregator());
        List<Pattern> viaExplicit = EmbeddingStoreMiner.mine(db, 3, 0.5, new MinAggregator(), prefilter, null);
        assertSamePatternSet(viaFacade, viaExplicit);
    }

    @Test
    void prefilterActuallySkipsSomething() {
        // On the illustrative dataset at sigma=6, tau=0.5, several label pairs
        // are dead under MIN: (A,A) doesn't exist; (A,C) has only 1 graph with
        // a heavy A-C; (C,D) has only 2 of 6 with C-D >= 0.5. The prefilter
        // should record at least one tuple kill.
        MiningStats stats = new MiningStats();
        EmbeddingStoreMiner.mine(db, 6, 0.5, new MinAggregator(), prefilter, stats);
        assertTrue(stats.extensionTuplesPrefilterSkipped > 0,
            "expected the prefilter to skip at least one label pair on illustrative @ sigma=6, "
                + "tau_w=0.5; got " + stats);
    }

    @Test
    void minWithoutPrefilterStillEmitsTheSameSet() {
        // Sanity for the ablation: mine() without prefilter and
        // mineWithPrefilter() must agree at every configuration we test.
        int[] sigmas = {2, 3, 5, 6};
        double tau = 0.5;
        for (int s : sigmas) {
            List<Pattern> a = EmbeddingStoreMiner.mine(db, s, tau, new MinAggregator());
            List<Pattern> b = EmbeddingStoreMiner.mineWithPrefilter(db, s, tau, new MinAggregator());
            assertEquals(toCodeSet(a), toCodeSet(b),
                "parity broken at sigma=" + s);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void assertSamePatternSet(List<Pattern> a, List<Pattern> b) {
        assertEquals(toCodeSet(a), toCodeSet(b),
            "prefilter must be a pure pruning optimization (no effect on output set)");
    }

    private static Set<String> toCodeSet(List<Pattern> patterns) {
        Set<String> s = new HashSet<>();
        for (Pattern p : patterns) s.add(p.code().toString());
        return s;
    }
}
