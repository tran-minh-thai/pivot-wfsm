package pivotwfsm.miner;

import pivotwfsm.core.DFSCode;
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
 * Hand-verifies the 1-edge enumeration against the illustrative dataset.
 *
 * <p>Total host edges in the database: 27 (asserted elsewhere in
 * IllustrativeDatasetLoadTest). For 1-edge patterns where the endpoint
 * labels differ, every host edge yields exactly one embedding, so the
 * embedding-count totals across all patterns must equal 27.
 */
class InitialPatternsTest {

    // Labels (mirror data/sample/illustrative.json).
    private static final int A = 0, B = 1, C = 2, D = 3;

    private static MultiGraphDB db;
    private static List<Pattern> patterns;

    @BeforeAll
    static void load() throws IOException {
        db = MultiGraphDB.loadJson(Paths.get("data/sample/illustrative.json"));
        patterns = InitialPatterns.enumerate(db);
    }

    @Test
    void enumerateProducesSixDistinctPatterns() {
        assertEquals(6, patterns.size(),
            "illustrative covers exactly {AB, AC, AD, BC, BD, CD} - no AA/BB/CC/DD edges");
    }

    @Test
    void totalEmbeddingsMatchTotalHostEdges() {
        long total = 0;
        for (Pattern p : patterns) {
            for (int gid : p.occursIn()) {
                total += p.embeddingsInGraph(gid).size();
            }
        }
        assertEquals(27L, total,
            "no same-label edges in the dataset, so each host edge yields exactly one embedding");
    }

    @Test
    void patternsAreSortedByLabelPair() {
        // Expected canonical order: (A,B) (A,C) (A,D) (B,C) (B,D) (C,D)
        int[][] expected = {
            {A, B}, {A, C}, {A, D}, {B, C}, {B, D}, {C, D}
        };
        for (int i = 0; i < expected.length; i++) {
            DFSCode.EdgeTuple t = patterns.get(i).code().edge(0);
            assertEquals(expected[i][0], t.fromLabel,
                "pattern " + i + " fromLabel mismatch");
            assertEquals(expected[i][1], t.toLabel,
                "pattern " + i + " toLabel mismatch");
        }
    }

    @Test
    void abPatternSupportAndPerGraphCounts() {
        Pattern ab = find(A, B);

        // Distribution of A-B embeddings across host graphs (from data/sample/README.md):
        Map<Integer, Integer> expected = Map.of(
            0, 2,   // G0 has two parallel A-B edges
            1, 1,
            2, 2,   // G2 has A-B0 and A-B1
            3, 1,
            4, 1,
            5, 2    // G5 has two parallel A-B edges
        );

        for (Map.Entry<Integer, Integer> en : expected.entrySet()) {
            assertEquals(en.getValue().intValue(),
                ab.embeddingsInGraph(en.getKey()).size(),
                "A-B embedding count mismatch in G" + en.getKey());
        }
        assertEquals(6, ab.support());
        assertEquals(5, ab.supportMin(0.5),
            "A-B under MIN at tau_w=0.5: G3 fails (weight 0.10) -> 5/6");
        assertEquals(5, ab.supportAvg(0.5),
            "A-B is a single edge so W_min == W_avg; same support");
    }

    @Test
    void abPatternBestWeights() {
        Pattern ab = find(A, B);
        // Best A-B weight per host graph.
        double[] expected = {0.85, 0.90, 0.75, 0.10, 0.65, 0.95};
        for (int g = 0; g < expected.length; g++) {
            assertEquals(expected[g], ab.bestWMin(g), 1e-12,
                "best A-B W_min in G" + g);
        }
    }

    @Test
    void acPatternIsRare() {
        Pattern ac = find(A, C);
        assertEquals(2, ac.support(), "A-C only in G3 and G4");
        assertEquals(1, ac.embeddingsInGraph(3).size());
        assertEquals(1, ac.embeddingsInGraph(4).size());
        assertEquals(0, ac.embeddingsInGraph(0).size());
        assertEquals(0.60, ac.bestWMin(3), 1e-12);
        assertEquals(0.40, ac.bestWMin(4), 1e-12);
        assertEquals(1, ac.supportMin(0.5),
            "G3 passes (0.60 >= 0.5); G4 fails (0.40 < 0.5) -> sigma_MIN(A-C) = 1");
    }

    @Test
    void bcPatternCounts() {
        Pattern bc = find(B, C);
        Map<Integer, Integer> expected = Map.of(
            0, 1,
            1, 1,
            2, 2,   // G2 has B0-C and B1-C - two distinct B-C edges
            3, 1,
            4, 1,
            5, 1
        );
        for (Map.Entry<Integer, Integer> en : expected.entrySet()) {
            assertEquals(en.getValue().intValue(),
                bc.embeddingsInGraph(en.getKey()).size(),
                "B-C count in G" + en.getKey());
        }
        assertEquals(6, bc.support());
    }

    @Test
    void cdPatternCounts() {
        Pattern cd = find(C, D);
        // C-D is in G1 (edge 2: C-D 0.80), G4 (edge 2: C-D 0.55), G5 (edge 3: C-D 0.40).
        // G0 and G2 do not have C-D. G3 has no D vertex.
        assertEquals(3, cd.support());
        assertEquals(0.80, cd.bestWMin(1), 1e-12);
        assertEquals(0.55, cd.bestWMin(4), 1e-12);
        assertEquals(0.40, cd.bestWMin(5), 1e-12);
        assertEquals(2, cd.supportMin(0.5),
            "G5 weight 0.40 < 0.5 fails; G1 and G4 pass");
    }

    private static Pattern find(int loLabel, int hiLabel) {
        for (Pattern p : patterns) {
            DFSCode.EdgeTuple t = p.code().edge(0);
            if (t.fromLabel == loLabel && t.toLabel == hiLabel) {
                return p;
            }
        }
        throw new AssertionError("pattern (" + loLabel + "," + hiLabel + ") not enumerated");
    }
}
