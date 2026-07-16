package pivotwfsm.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static pivotwfsm.core.DFSCode.EdgeTuple;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Hand-constructs the embedding lists for the five ground-truth patterns
 * from {@code data/sample/ground_truth.json} and asserts that the
 * {@link Pattern} aggregator API returns the documented numbers exactly.
 *
 * <p>The miner is not exercised here - this test verifies the
 * <em>scoring</em> half of the pipeline against an independent calculation,
 * so when the miner is wired in later, any mismatch points squarely at the
 * miner rather than at the aggregator.
 */
class PatternGroundTruthTest {

    // Labels (mirror data/sample/illustrative.json).
    private static final int A = 0, B = 1, C = 2, D = 3;
    private static final int E = 0;

    private static final double TAU_W = 0.5;
    private static final double EPS = 1e-9;

    private static MultiGraphDB db;

    @BeforeAll
    static void load() throws IOException {
        db = MultiGraphDB.loadJson(Paths.get("data/sample/illustrative.json"));
    }

    // ---------------------------------------------------------------- P1: A-B

    @Test
    void p1SingleEdgeAB() {
        DFSCode code = DFSCode.of(new EdgeTuple(0, 1, A, E, B));
        List<Embedding> embs = new ArrayList<>();
        // Best A-B embedding per graph (vertex map = {A_host, B_host}).
        embs.add(Embedding.of(0, db.get(0), new int[]{0, 1}, new int[]{0})); // 0.85
        embs.add(Embedding.of(1, db.get(1), new int[]{0, 1}, new int[]{0})); // 0.90
        embs.add(Embedding.of(2, db.get(2), new int[]{0, 1}, new int[]{0})); // 0.75
        embs.add(Embedding.of(3, db.get(3), new int[]{0, 1}, new int[]{0})); // 0.10
        embs.add(Embedding.of(4, db.get(4), new int[]{0, 1}, new int[]{0})); // 0.65
        embs.add(Embedding.of(5, db.get(5), new int[]{0, 1}, new int[]{1})); // 0.95

        Pattern p = Pattern.of(code, embs);
        assertEquals(6, p.support(), "P1 occurs in all 6 graphs");
        assertEquals(5, p.supportMin(TAU_W), "P1: G3 fails (0.10 < 0.5) -> 5/6 under MIN");
        assertEquals(5, p.supportAvg(TAU_W), "P1: single edge, W_min == W_avg, same count");
    }

    // ---------------------------------------------------------------- P2: A-B-C  (the divergence highlight)

    @Test
    void p2PathABC_MIN_vs_AVG_diverge() {
        DFSCode code = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C)
        );

        // Best embedding per graph (hand-computed; mirrors data/sample/README.md).
        List<Embedding> embs = Arrays.asList(
            Embedding.of(0, db.get(0), new int[]{0, 1, 2}, new int[]{0, 2}), // 0.85, 0.70 -> 0.70
            Embedding.of(1, db.get(1), new int[]{0, 1, 2}, new int[]{0, 1}), // 0.90, 0.60 -> 0.60
            Embedding.of(2, db.get(2), new int[]{0, 1, 2}, new int[]{0, 1}), // 0.75, 0.85 -> 0.75
            Embedding.of(3, db.get(3), new int[]{0, 1, 2}, new int[]{0, 1}), // 0.10, 0.95 -> 0.10
            Embedding.of(4, db.get(4), new int[]{0, 1, 2}, new int[]{0, 1}), // 0.65, 0.80 -> 0.65
            Embedding.of(5, db.get(5), new int[]{0, 1, 2}, new int[]{1, 2})  // 0.95, 0.50 -> 0.50
        );

        Pattern p = Pattern.of(code, embs);

        assertEquals(6, p.support());
        assertEquals(5, p.supportMin(TAU_W),
            "MIN rejects G3 (W_min=0.10 < 0.5)");
        assertEquals(6, p.supportAvg(TAU_W),
            "AVG accepts G3 (W_avg=0.525 >= 0.5) -- this is the MIN-vs-AVG divergence");

        // Per-graph best W_min sanity check (precision tight to make rounding bugs visible).
        assertEquals(0.70, p.bestWMin(0), EPS);
        assertEquals(0.60, p.bestWMin(1), EPS);
        assertEquals(0.75, p.bestWMin(2), EPS);
        assertEquals(0.10, p.bestWMin(3), EPS);
        assertEquals(0.65, p.bestWMin(4), EPS);
        assertEquals(0.50, p.bestWMin(5), EPS);

        // Per-graph best W_avg sanity check.
        assertEquals(0.775, p.bestWAvg(0), EPS);
        assertEquals(0.75,  p.bestWAvg(1), EPS);
        assertEquals(0.80,  p.bestWAvg(2), EPS);
        assertEquals(0.525, p.bestWAvg(3), EPS);
        assertEquals(0.725, p.bestWAvg(4), EPS);
        assertEquals(0.725, p.bestWAvg(5), EPS);
    }

    // ---------------------------------------------------------------- P3: A-B-D

    @Test
    void p3PathABD() {
        DFSCode code = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, D)
        );
        List<Embedding> embs = Arrays.asList(
            Embedding.of(0, db.get(0), new int[]{0, 1, 3}, new int[]{0, 4}), // 0.85, 0.50 -> 0.50
            Embedding.of(1, db.get(1), new int[]{0, 1, 3}, new int[]{0, 3}), // 0.90, 0.55 -> 0.55
            Embedding.of(2, db.get(2), new int[]{0, 1, 3}, new int[]{0, 2})  // 0.75, 0.70 -> 0.70
        );
        Pattern p = Pattern.of(code, embs);
        assertEquals(3, p.support());
        assertEquals(3, p.supportMin(TAU_W));
        assertEquals(3, p.supportAvg(TAU_W));
    }

    // ---------------------------------------------------------------- P4: triangle A-B-C

    @Test
    void p4TriangleABC_isRare() {
        DFSCode code = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C),
            new EdgeTuple(2, 0, C, E, A)
        );
        // Only G3 and G4 contain the triangle (P4 needs A-C edge too).
        List<Embedding> embs = Arrays.asList(
            Embedding.of(3, db.get(3), new int[]{0, 1, 2}, new int[]{0, 1, 2}), // 0.10,0.95,0.60 -> 0.10
            Embedding.of(4, db.get(4), new int[]{0, 1, 2}, new int[]{0, 1, 4})  // 0.65,0.80,0.40 -> 0.40
        );
        Pattern p = Pattern.of(code, embs);
        assertEquals(2, p.support());
        assertEquals(0, p.supportMin(TAU_W), "G3 fails (0.10) and G4 fails (0.40) under tau_w=0.5");
        assertEquals(2, p.supportAvg(TAU_W),
            "G3 avg=(0.10+0.95+0.60)/3=0.55 >= 0.5 OK; G4 avg=(0.65+0.80+0.40)/3=0.617 OK; both pass - "
                + "second MIN-vs-AVG divergence: at sigma_min=2, tau_w=0.5, AVG returns P4 but MIN does not");
    }

    // ---------------------------------------------------------------- P5: star B->{A,C,D}

    @Test
    void p5StarB_to_ACD() {
        DFSCode code = DFSCode.of(
            new EdgeTuple(0, 1, B, E, A),
            new EdgeTuple(0, 2, B, E, C),
            new EdgeTuple(0, 3, B, E, D)
        );
        // Pattern vertex map: 0->B_host, 1->A_host, 2->C_host, 3->D_host
        List<Embedding> embs = Arrays.asList(
            // G0: B=v1, A=v0, C=v2, D=v3. Edges: 0 (A-B 0.85), 2 (B-C 0.70), 4 (D-B 0.50)
            Embedding.of(0, db.get(0), new int[]{1, 0, 2, 3}, new int[]{0, 2, 4}),
            // G1: B=v1, A=v0, C=v2, D=v3. Edges: 0 (A-B 0.90), 1 (B-C 0.60), 3 (B-D 0.55)
            Embedding.of(1, db.get(1), new int[]{1, 0, 2, 3}, new int[]{0, 1, 3}),
            // G2 via B0=v1: A=v0, C=v2, D=v3. Edges: 0 (B-A 0.75), 1 (B-C 0.85), 2 (B-D 0.70)
            Embedding.of(2, db.get(2), new int[]{1, 0, 2, 3}, new int[]{0, 1, 2})
        );
        Pattern p = Pattern.of(code, embs);
        assertEquals(3, p.support());
        assertEquals(3, p.supportMin(TAU_W));
        assertEquals(3, p.supportAvg(TAU_W));

        assertEquals(0.50, p.bestWMin(0), EPS);
        assertEquals(0.55, p.bestWMin(1), EPS);
        assertEquals(0.70, p.bestWMin(2), EPS);
    }
}
