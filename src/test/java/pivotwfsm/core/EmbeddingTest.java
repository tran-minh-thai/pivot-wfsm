package pivotwfsm.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Embedding.of() computes W_min and W_avg correctly using
 * hand-picked vertex/edge maps over the illustrative dataset.
 */
class EmbeddingTest {

    private static MultiGraphDB db;

    @BeforeAll
    static void load() throws IOException {
        db = MultiGraphDB.loadJson(Paths.get("data/sample/illustrative.json"));
    }

    @Test
    void p2BestEmbeddingInG0() {
        // P2 = path A-B-C. Best embedding in G0 picks the strong A-B edge (idx 0, w=0.85)
        // and the only B-C edge (idx 2, w=0.70). W_min = 0.70, W_avg = 0.775.
        Embedding emb = Embedding.of(0, db.get(0),
            new int[]{0, 1, 2}, new int[]{0, 2});
        assertEquals(0.70, emb.wMin(), 1e-12);
        assertEquals(0.775, emb.wAvg(), 1e-12);
    }

    @Test
    void p2WeakEmbeddingInG0() {
        // Same vertex map but choose the WEAK parallel A-B edge (idx 1, w=0.40).
        // Demonstrates that the multigraph yields multiple embeddings.
        Embedding emb = Embedding.of(0, db.get(0),
            new int[]{0, 1, 2}, new int[]{1, 2});
        assertEquals(0.40, emb.wMin(), 1e-12);
        assertEquals(0.55, emb.wAvg(), 1e-12);
    }

    @Test
    void p2EmbeddingInG3IsTheWeakLinkCase() {
        // G3 is the linchpin of the MIN-vs-AVG divergence.
        // Edge 0 has w=0.10 (the weak A-B), edge 1 has w=0.95 (B-C).
        Embedding emb = Embedding.of(3, db.get(3),
            new int[]{0, 1, 2}, new int[]{0, 1});
        assertEquals(0.10, emb.wMin(), 1e-12);
        assertEquals(0.525, emb.wAvg(), 1e-12);
    }

    @Test
    void p2EmbeddingInG2ViaB0() {
        // G2 has two B-labeled vertices. The strong path goes through B0=v1.
        // edge idx 0: B0-A (w=0.75); edge idx 1: B0-C (w=0.85).
        Embedding emb = Embedding.of(2, db.get(2),
            new int[]{0, 1, 2}, new int[]{0, 1});
        assertEquals(0.75, emb.wMin(), 1e-12);
        assertEquals(0.80, emb.wAvg(), 1e-12);
    }

    @Test
    void p2EmbeddingInG2ViaB1() {
        // The weak alternative path uses B1=v4. Edge idx 4: A-B1 (w=0.45);
        // edge idx 3: B1-C (w=0.95).
        Embedding emb = Embedding.of(2, db.get(2),
            new int[]{0, 4, 2}, new int[]{4, 3});
        assertEquals(0.45, emb.wMin(), 1e-12);
        assertEquals(0.70, emb.wAvg(), 1e-12);
    }

    @Test
    void p2BestEmbeddingInG5UsesHeavyParallelAB() {
        // G5 has two parallel A-B edges (w=0.55 idx0, w=0.95 idx1). Best
        // embedding picks the heavier one.
        Embedding emb = Embedding.of(5, db.get(5),
            new int[]{0, 1, 2}, new int[]{1, 2});
        assertEquals(0.50, emb.wMin(), 1e-12);  // B-C(0.50) is the bottleneck
        assertEquals(0.725, emb.wAvg(), 1e-12);
    }

    @Test
    void embeddingEqualityIsStructural() {
        Embedding a = Embedding.of(0, db.get(0),
            new int[]{0, 1, 2}, new int[]{0, 2});
        Embedding b = Embedding.of(0, db.get(0),
            new int[]{0, 1, 2}, new int[]{0, 2});
        Embedding parallelChoice = Embedding.of(0, db.get(0),
            new int[]{0, 1, 2}, new int[]{1, 2}); // different edge - different embedding
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, parallelChoice);
    }

    @Test
    void p4TriangleInG4() {
        // Triangle A-B-C in G4. Edges 0,1,4 form the triangle:
        // 0: A-B 0.65; 1: B-C 0.80; 4: A-C 0.40. W_min = 0.40.
        Embedding emb = Embedding.of(4, db.get(4),
            new int[]{0, 1, 2}, new int[]{0, 1, 4});
        assertEquals(0.40, emb.wMin(), 1e-12);
        assertEquals((0.65 + 0.80 + 0.40) / 3.0, emb.wAvg(), 1e-12);
    }
}
