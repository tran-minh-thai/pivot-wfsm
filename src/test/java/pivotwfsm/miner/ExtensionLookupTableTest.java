package pivotwfsm.miner;

import pivotwfsm.core.MultiGraphDB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spot-checks the prefilter contents against the illustrative dataset's
 * hand-computed numbers. If these counts drift, every downstream prefilter
 * decision will silently drift with them.
 */
class ExtensionLookupTableTest {

    private static final int A = 0, B = 1, C = 2, D = 3;

    private static MultiGraphDB db;
    private static ExtensionLookupTable prefilter;

    @BeforeAll
    static void load() throws IOException {
        db = MultiGraphDB.loadJson(Paths.get("data/sample/illustrative.json"));
        prefilter = ExtensionLookupTable.build(db);
    }

    @Test
    void enumeratesAllLabelPairsPresentInDataset() {
        List<int[]> pairs = prefilter.labelPairs();
        assertEquals(6, pairs.size(),
            "illustrative has 6 distinct undirected label pairs {AB,AC,AD,BC,BD,CD}");

        // Confirm none of {AA, BB, CC, DD} are listed (no same-label edges).
        for (int[] p : pairs) {
            assertNotEquals(p[0], p[1],
                "no self-pair like (A,A) should be enumerated for this dataset");
        }
    }

    @Test
    void existenceTest() {
        assertTrue(prefilter.exists(A, B));
        assertTrue(prefilter.exists(B, A), "should be order-invariant");
        assertTrue(prefilter.exists(B, C));
        assertTrue(prefilter.exists(A, D));
        // A-A does not exist in the dataset.
        assertFalse(prefilter.exists(A, A));
        // B-B does not exist (G2 has two B-labeled vertices but no edge between them).
        assertFalse(prefilter.exists(B, B));
    }

    @Test
    void graphsWithEdge_AB() {
        // A-B edges exist in all 6 graphs. Best weights per graph:
        // G0: 0.85, G1: 0.90, G2: 0.75, G3: 0.10, G4: 0.65, G5: 0.95
        assertEquals(6, prefilter.graphsWithEdge(A, B, 0.0));
        assertEquals(5, prefilter.graphsWithEdge(A, B, 0.5),  "G3 (0.10) fails at tau=0.5");
        assertEquals(4, prefilter.graphsWithEdge(A, B, 0.70), "G3, G4 (0.65) fail at tau=0.70");
        assertEquals(2, prefilter.graphsWithEdge(A, B, 0.90), "only G1 (0.90) and G5 (0.95)");
        assertEquals(0, prefilter.graphsWithEdge(A, B, 0.96));
    }

    @Test
    void graphsWithEdge_AC_isRare() {
        // A-C edges are only in G3 (0.60) and G4 (0.40).
        assertEquals(2, prefilter.graphsWithEdge(A, C, 0.0));
        assertEquals(1, prefilter.graphsWithEdge(A, C, 0.5),
            "G3 (0.60) passes, G4 (0.40) fails at tau=0.5");
        assertEquals(0, prefilter.graphsWithEdge(A, C, 0.65));
    }

    @Test
    void canExtendIsTheUnifiedDecision() {
        // canExtend = graphsWithEdge(...) >= sigma_min - verify on the
        // thresholds that drive the measure-divergence case.
        assertTrue(prefilter.canExtend(A, B, 0.5, 5));   // sigma=5, tau=0.5 -> 5 graphs OK
        assertFalse(prefilter.canExtend(A, B, 0.5, 6));  // sigma=6 -> only 5 -> dead

        assertFalse(prefilter.canExtend(A, C, 0.5, 2));  // only 1 graph has A-C w >= 0.5
        assertFalse(prefilter.canExtend(A, A, 0.0, 1),
            "no A-A edge exists; prefilter must kill this pair at any threshold");
    }
}
