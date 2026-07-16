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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies right-most extension correctness against the illustrative
 * dataset's ground truth. Drives the extension exactly once (P1 -&gt; P2)
 * for sanity, then a second time (P2 -&gt; P4 triangle) to exercise the
 * backward-edge branch.
 */
class RightMostExtensionTest {

    private static final int A = 0, B = 1, C = 2, D = 3;
    private static final int E = 0;
    private static final double EPS = 1e-12;

    private static MultiGraphDB db;

    @BeforeAll
    static void load() throws IOException {
        db = MultiGraphDB.loadJson(Paths.get("data/sample/illustrative.json"));
    }

    // ---------------------------------------------------------------- P1 -> P2

    @Test
    void p1ExtensionsContainPathABC() {
        // Seed: pattern (A,_,B) with all its embeddings from InitialPatterns.
        Pattern p1ab = oneEdge(A, B);

        Map<EdgeTuple, Pattern> children = RightMostExtension.extend(p1ab, db);

        EdgeTuple expectedABC = new EdgeTuple(1, 2, B, E, C);
        assertTrue(children.containsKey(expectedABC),
            "Path A-B-C must be among the forward extensions of A-B");

        Pattern abc = children.get(expectedABC);
        assertEquals(6, abc.support(), "path A-B-C occurs in all 6 graphs");
        assertEquals(5, abc.supportMin(0.5),
            "MIN rejects G3 (weak A-B 0.10) -> 5/6");
        assertEquals(6, abc.supportAvg(0.5),
            "AVG accepts G3 (avg = 0.525) -> 6/6 (the MIN-vs-AVG divergence)");

        // Spot-check per-graph best W_min equals what we hand-computed.
        assertEquals(0.70, abc.bestWMin(0), EPS);
        assertEquals(0.60, abc.bestWMin(1), EPS);
        assertEquals(0.75, abc.bestWMin(2), EPS);
        assertEquals(0.10, abc.bestWMin(3), EPS);
        assertEquals(0.65, abc.bestWMin(4), EPS);
        assertEquals(0.50, abc.bestWMin(5), EPS);
    }

    @Test
    void p1ExtensionsIncludeABD() {
        Pattern p1ab = oneEdge(A, B);
        Map<EdgeTuple, Pattern> children = RightMostExtension.extend(p1ab, db);

        // (1, 2, B, _, D) = path A-B-D
        EdgeTuple abd = new EdgeTuple(1, 2, B, E, D);
        assertTrue(children.containsKey(abd), "A-B-D should be enumerated");
        Pattern path = children.get(abd);
        assertEquals(3, path.support(), "A-B-D occurs in G0, G1, G2 only");
    }

    @Test
    void p1ExtensionsAllStartWithBoundedNumberOfTuples() {
        // Sanity guard: the extension fan-out from a 1-edge seed in the
        // illustrative dataset stays small (10s, not 100s). If this number
        // jumps, suspect a bug in usedHostV/usedHostE bookkeeping.
        Pattern p1ab = oneEdge(A, B);
        Map<EdgeTuple, Pattern> children = RightMostExtension.extend(p1ab, db);
        assertTrue(children.size() <= 16,
            "Unexpectedly many children: " + children.size()
                + " - investigate forward/backward extension bookkeeping");
    }

    // ---------------------------------------------------------------- P2 -> P4 (triangle)

    @Test
    void p2ExtensionsContainTriangleABC() {
        // First go P1 -> P2 to get the ABC path, then extend it again.
        Pattern p1ab = oneEdge(A, B);
        Pattern p2abc = RightMostExtension.extend(p1ab, db).get(new EdgeTuple(1, 2, B, E, C));
        assertNotNull(p2abc, "ABC path must be enumerated by P1 extension");

        Map<EdgeTuple, Pattern> children = RightMostExtension.extend(p2abc, db);

        // Triangle A-B-C closes with backward edge (2, 0, C, _, A).
        EdgeTuple triangle = new EdgeTuple(2, 0, C, E, A);
        assertTrue(children.containsKey(triangle),
            "Triangle A-B-C should appear as a BACKWARD extension of path A-B-C");

        Pattern p4 = children.get(triangle);
        assertEquals(2, p4.support(), "triangle A-B-C exists in G3 and G4 only");

        // From ground_truth.json: best W_min(G3)=0.10, best W_min(G4)=0.40.
        assertEquals(0.10, p4.bestWMin(3), EPS);
        assertEquals(0.40, p4.bestWMin(4), EPS);

        assertEquals(0, p4.supportMin(0.5),
            "MIN: G3 fails (0.10) AND G4 fails (0.40) - triangle vanishes");
        assertEquals(2, p4.supportAvg(0.5),
            "AVG: G3 avg=0.55 OK; G4 avg=0.617 OK - both pass. Second MIN-vs-AVG divergence.");
    }

    // ---------------------------------------------------------------- helper

    private static Pattern oneEdge(int loLabel, int hiLabel) {
        List<Pattern> seeds = InitialPatterns.enumerate(db);
        for (Pattern p : seeds) {
            DFSCode.EdgeTuple t = p.code().edge(0);
            if (t.fromLabel == loLabel && t.toLabel == hiLabel) return p;
        }
        throw new AssertionError("1-edge pattern not in seed list");
    }
}
