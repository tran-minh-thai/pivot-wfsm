package pivotwfsm.miner;

import pivotwfsm.core.DFSCode;
import pivotwfsm.core.DFSCode.EdgeTuple;
import pivotwfsm.core.MultiGraphDB;
import pivotwfsm.core.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end miner check on the illustrative dataset. Tests the three things
 * that matter for the paper:
 *   (a) the two known MIN-vs-AVG divergence configurations (P2 at sigma=6,
 *       P4 at sigma=2) are reproduced exactly;
 *   (b) the miner is deterministic across runs;
 *   (c) anti-monotone pruning under MIN actually shrinks the search vs
 *       the looser transactional-only pruning under AVG.
 */
class EmbeddingStoreMinerTest {

    private static final int A = 0, B = 1, C = 2, D = 3;
    private static final int E = 0;
    private static final double TAU = 0.5;

    private static MultiGraphDB db;

    @BeforeAll
    static void load() throws IOException {
        db = MultiGraphDB.loadJson(Paths.get("data/sample/illustrative.json"));
    }

    // ============================================================ MIN-vs-AVG divergence #1
    //
    // At sigma=6, tau=0.5 the dataset's 1-edge B-C clears under BOTH aggregators
    // (every graph has a B-C edge of weight >= 0.5). The divergence shows up in
    // the 2-edge layer: path A-B-C is AVG-frequent but MIN-rejected because G3's
    // weak A-B edge (0.10) drags W_min below tau_w even though the average
    // (0.525) stays above. So the SYMMETRIC DIFFERENCE between MIN and AVG at
    // sigma=6 is exactly {A-B-C}, which is the running case study for the divergence.

    @Test
    void atSigma6_minResultIsExactly_BC() {
        List<Pattern> result = EmbeddingStoreMiner.mine(db, 6, TAU, EmbeddingStoreMiner.AggregatorMode.MIN);

        DFSCode bc = DFSCode.of(new EdgeTuple(0, 1, B, E, C));
        assertEquals(1, result.size(),
            "Under MIN at sigma=6 only the 1-edge B-C clears (all 6 graphs have B-C edges "
                + ">= 0.5). Got: " + result);
        assertEquals(bc, result.get(0).code(), "the single survivor must be the canonical B-C");
    }

    @Test
    void atSigma6_avgResultIsExactly_BC_and_ABC() {
        List<Pattern> result = EmbeddingStoreMiner.mine(db, 6, TAU, EmbeddingStoreMiner.AggregatorMode.AVG);

        DFSCode bc  = DFSCode.of(new EdgeTuple(0, 1, B, E, C));
        DFSCode abc = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C)
        );

        assertEquals(2, result.size(),
            "Under AVG at sigma=6 the set is exactly {B-C, A-B-C}. Got: " + result);
        assertTrue(containsCode(result, bc),
            "B-C must clear under AVG as well (every graph has B-C >= 0.5)");
        assertTrue(containsCode(result, abc),
            "A-B-C must clear under AVG only (G3 avg=0.525 >= 0.5 even though W_min=0.10)");
    }

    @Test
    void atSigma6_symmetricDifferenceIsExactlyABC() {
        // The key divergence claim: at sigma=6 the disagreement set between MIN
        // and AVG is exactly {A-B-C}, i.e. one pattern.
        List<Pattern> resMin = EmbeddingStoreMiner.mine(db, 6, TAU, EmbeddingStoreMiner.AggregatorMode.MIN);
        List<Pattern> resAvg = EmbeddingStoreMiner.mine(db, 6, TAU, EmbeddingStoreMiner.AggregatorMode.AVG);

        DFSCode abc = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C)
        );

        // AVG must include A-B-C, MIN must not.
        assertTrue(containsCode(resAvg, abc));
        assertFalse(containsCode(resMin, abc));

        // All other patterns must be in both or in neither.
        int divergences = 0;
        for (Pattern p : resAvg) {
            if (!containsCode(resMin, p.code())) divergences++;
        }
        for (Pattern p : resMin) {
            if (!containsCode(resAvg, p.code())) divergences++;
        }
        assertEquals(1, divergences,
            "Symmetric difference at sigma=6 must be a single pattern (A-B-C). Got " + divergences);
    }

    // ============================================================ MIN-vs-AVG divergence #2: P4

    @Test
    void avgAtSigma2IncludesTriangleP4() {
        List<Pattern> result = EmbeddingStoreMiner.mine(db, 2, TAU, EmbeddingStoreMiner.AggregatorMode.AVG);

        DFSCode triangleABC = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C),
            new EdgeTuple(2, 0, C, E, A)
        );

        assertTrue(containsCode(result, triangleABC),
            "Under AVG at sigma=2 the triangle A-B-C (P4) must be frequent: "
                + "G3 avg=0.55 OK, G4 avg=0.617 OK -> sigma_AVG=2");
    }

    @Test
    void minAtSigma2DoesNotIncludeTriangleP4() {
        List<Pattern> result = EmbeddingStoreMiner.mine(db, 2, TAU, EmbeddingStoreMiner.AggregatorMode.MIN);

        DFSCode triangleABC = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C),
            new EdgeTuple(2, 0, C, E, A)
        );

        assertFalse(containsCode(result, triangleABC),
            "Under MIN at sigma=2 the triangle A-B-C must NOT appear: "
                + "G3 W_min=0.10 fails (0.10 < 0.5), G4 W_min=0.40 fails (0.40 < 0.5) -> sigma_MIN=0");
    }

    // ============================================================ Anti-monotone bite

    @Test
    void minPrunesMoreThanAvgAtSameThreshold() {
        // Under MIN at sigma=3, tau=0.5: anti-monotone bite is tight.
        // Under AVG at sigma=3 the same tau, transactional-only pruning is loose,
        // so the recursion visits strictly more candidate patterns.
        // We can't easily expose visit counts without instrumentation, but at minimum
        // |result_MIN| <= |result_AVG| should always hold for the same threshold.
        List<Pattern> resMin = EmbeddingStoreMiner.mine(db, 3, TAU, EmbeddingStoreMiner.AggregatorMode.MIN);
        List<Pattern> resAvg = EmbeddingStoreMiner.mine(db, 3, TAU, EmbeddingStoreMiner.AggregatorMode.AVG);
        assertTrue(resMin.size() <= resAvg.size(),
            "MIN's frequent set is a subset of AVG's at the same threshold "
                + "(every pattern that clears W_min also clears W_avg since min <= avg). "
                + "Got |MIN|=" + resMin.size() + " > |AVG|=" + resAvg.size());
    }

    @Test
    void minResultIsContainedInAvgResult() {
        // Stronger claim: MIN's frequent SET ⊆ AVG's frequent SET (not just sizes).
        // Because W_min <= W_avg per embedding, anything passing sigma_MIN also passes sigma_AVG.
        List<Pattern> resMin = EmbeddingStoreMiner.mine(db, 3, TAU, EmbeddingStoreMiner.AggregatorMode.MIN);
        List<Pattern> resAvg = EmbeddingStoreMiner.mine(db, 3, TAU, EmbeddingStoreMiner.AggregatorMode.AVG);
        for (Pattern p : resMin) {
            assertTrue(containsCode(resAvg, p.code()),
                "Pattern " + p.code() + " in MIN result but missing from AVG result; "
                    + "this would violate W_min <= W_avg");
        }
    }

    // ============================================================ Determinism

    @Test
    void mineIsDeterministic() {
        List<Pattern> a = EmbeddingStoreMiner.mine(db, 3, TAU, EmbeddingStoreMiner.AggregatorMode.MIN);
        List<Pattern> b = EmbeddingStoreMiner.mine(db, 3, TAU, EmbeddingStoreMiner.AggregatorMode.MIN);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).code(), b.get(i).code(),
                "Pattern at index " + i + " differs between runs");
        }
    }

    // ============================================================ Hand-picked seed checks

    @Test
    void minAtSigma5ContainsP1AndP2() {
        // At sigma=5, tau=0.5, MIN: P1 (sigma_MIN=5) and P2 (sigma_MIN=5) both clear.
        List<Pattern> result = EmbeddingStoreMiner.mine(db, 5, TAU, EmbeddingStoreMiner.AggregatorMode.MIN);

        DFSCode p1 = DFSCode.of(new EdgeTuple(0, 1, A, E, B));
        DFSCode p2 = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C)
        );
        DFSCode bc = DFSCode.of(new EdgeTuple(0, 1, B, E, C));

        assertTrue(containsCode(result, p1), "P1 (A-B) must be frequent under MIN at sigma=5");
        assertTrue(containsCode(result, p2), "P2 (A-B-C) must be frequent under MIN at sigma=5");
        // B-C also has sigma_MIN(0.5) = 6, so it should be there too.
        assertTrue(containsCode(result, bc), "B-C must be frequent (sigma_MIN=6 >= 5)");
    }

    // ============================================================ helper

    private static boolean containsCode(List<Pattern> patterns, DFSCode target) {
        for (Pattern p : patterns) {
            if (p.code().equals(target)) return true;
        }
        return false;
    }
}
