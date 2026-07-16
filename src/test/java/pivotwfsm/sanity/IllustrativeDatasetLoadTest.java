package pivotwfsm.sanity;

import pivotwfsm.core.MultiGraph;
import pivotwfsm.core.MultiGraphDB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loads {@code data/sample/illustrative.json} and asserts the structure
 * matches {@code data/sample/README.md} byte-for-byte (counts, parallel
 * edges, two B-labeled vertices in G2).
 *
 * <p>This is a sanity test on the LOADER. The miner-output ground-truth
 * test will live in {@code IllustrativeMinerGroundTruthTest} once the
 * algorithm is implemented.
 */
class IllustrativeDatasetLoadTest {

    private static MultiGraphDB db;

    @BeforeAll
    static void load() throws IOException {
        Path p = Paths.get("data/sample/illustrative.json");
        assertTrue(p.toFile().exists(),
            "Expected " + p.toAbsolutePath() + " to exist. Run tests from project root.");
        db = MultiGraphDB.loadJson(p);
    }

    @Test
    void databaseShape() {
        assertEquals("illustrative", db.name());
        assertEquals(6, db.size(), "expected 6 graphs");
        assertEquals(27, db.totalEdges(), "expected 27 edges total across the 6 graphs");
        assertEquals(24, db.totalVertices(), "expected 24 vertices total");
    }

    @Test
    void labelStrings() {
        assertEquals(4, db.labelStrings().size());
        assertEquals("A", db.labelStrings().get(0));
        assertEquals("B", db.labelStrings().get(1));
        assertEquals("C", db.labelStrings().get(2));
        assertEquals("D", db.labelStrings().get(3));
    }

    @Test
    void perGraphShape() {
        int[] expectedV = {4, 4, 5, 3, 4, 4};
        int[] expectedE = {5, 4, 5, 3, 5, 5};
        for (int i = 0; i < db.size(); i++) {
            MultiGraph g = db.get(i);
            assertEquals(i, g.id(), "graph " + i + " id mismatch");
            assertEquals(expectedV[i], g.numVertices(),
                "graph " + i + " |V| mismatch");
            assertEquals(expectedE[i], g.numEdges(),
                "graph " + i + " |E| mismatch");
        }
    }

    @Test
    void g0HasTwoParallelABEdges() {
        MultiGraph g0 = db.get(0);
        int parallelABEdges = 0;
        for (int e = 0; e < g0.numEdges(); e++) {
            int sLabel = g0.vertexLabel(g0.edgeSrc(e));
            int dLabel = g0.vertexLabel(g0.edgeDst(e));
            if ((sLabel == 0 && dLabel == 1) || (sLabel == 1 && dLabel == 0)) {
                parallelABEdges++;
            }
        }
        assertEquals(2, parallelABEdges,
            "G0 must have exactly 2 parallel A-B edges (the multigraph evidence)");
    }

    @Test
    void g2HasTwoBLabeledVertices() {
        MultiGraph g2 = db.get(2);
        int countB = 0;
        for (int v = 0; v < g2.numVertices(); v++) {
            if (g2.vertexLabel(v) == 1) {
                countB++;
            }
        }
        assertEquals(2, countB,
            "G2 must have 2 vertices labeled B (used to test label disambiguation)");
    }

    @Test
    void g3IsTheWeakLinkCase() {
        MultiGraph g3 = db.get(3);
        assertEquals(3, g3.numVertices());
        // G3 must have an A-B edge with weight 0.10 - the cause of the MIN vs AVG divergence.
        boolean foundWeakAB = false;
        for (int e = 0; e < g3.numEdges(); e++) {
            int sL = g3.vertexLabel(g3.edgeSrc(e));
            int dL = g3.vertexLabel(g3.edgeDst(e));
            boolean isAB = (sL == 0 && dL == 1) || (sL == 1 && dL == 0);
            if (isAB && Math.abs(g3.edgeWeight(e) - 0.10) < 1e-9) {
                foundWeakAB = true;
                break;
            }
        }
        assertTrue(foundWeakAB,
            "G3 must contain the weak A-B edge (weight 0.10). This is the linchpin "
                + "of the MIN-vs-AVG case study.");
    }

    @Test
    void edgeWeightsAreInUnitInterval() {
        for (MultiGraph g : db.graphs()) {
            for (int e = 0; e < g.numEdges(); e++) {
                double w = g.edgeWeight(e);
                assertTrue(w >= 0.0 && w <= 1.0,
                    "graph " + g.id() + " edge " + e + " weight " + w
                        + " outside [0,1] - would break weight-distribution assumptions");
            }
        }
    }

    @Test
    void labelHistogramPerGraph() {
        // From README.md: G2 is the only graph with 2 B-labeled vertices.
        // Verify the label histograms match the documented shape.
        int[][] expected = {
            //   A  B  C  D
            {    1, 1, 1, 1 }, // G0
            {    1, 1, 1, 1 }, // G1
            {    1, 2, 1, 1 }, // G2  ← 2 B's
            {    1, 1, 1, 0 }, // G3  no D
            {    1, 1, 1, 1 }, // G4
            {    1, 1, 1, 1 }, // G5
        };
        for (int i = 0; i < db.size(); i++) {
            Map<Integer, Integer> hist = new HashMap<>();
            MultiGraph g = db.get(i);
            for (int v = 0; v < g.numVertices(); v++) {
                hist.merge(g.vertexLabel(v), 1, Integer::sum);
            }
            for (int lbl = 0; lbl < 4; lbl++) {
                assertEquals(expected[i][lbl], hist.getOrDefault(lbl, 0),
                    "graph " + i + " label " + lbl + " count mismatch");
            }
        }
    }
}
