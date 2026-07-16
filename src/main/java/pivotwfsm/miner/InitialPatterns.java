package pivotwfsm.miner;

import pivotwfsm.core.DFSCode;
import pivotwfsm.core.DFSCode.EdgeTuple;
import pivotwfsm.core.Embedding;
import pivotwfsm.core.MultiGraph;
import pivotwfsm.core.MultiGraphDB;
import pivotwfsm.core.Pattern;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Seed of the gSpan search tree: enumerate every 1-edge canonical pattern
 * present in the database, together with its embedding list.
 *
 * <p>For an undirected edge between host vertices {@code (u, v)} with
 * labels {@code (lu, lv)} we group it under the canonical key
 * {@code (min(lu, lv), max(lu, lv))} so that {@code A-B} and {@code B-A}
 * both fall into the same pattern bucket.
 *
 * <p>If {@code lu == lv} the host edge yields <b>two</b> embeddings - once
 * with {@code vertexMap = [u, v]} and once with {@code [v, u]} - because
 * both orderings are valid in the absence of label asymmetry.
 *
 * <p>The edge label is unused in the illustrative dataset and in TUDataset
 * inputs we ingest; we encode it as the constant {@link #EDGE_LABEL_NONE}
 * to keep the {@link EdgeTuple} schema uniform.
 */
public final class InitialPatterns {

    /** Sentinel edge label for edge-unlabeled multigraphs. */
    public static final int EDGE_LABEL_NONE = 0;

    private InitialPatterns() {}

    /**
     * Enumerate all 1-edge canonical patterns in {@code db}.
     *
     * @return one {@link Pattern} per distinct {@code (lo_label, hi_label)}
     *         pair, sorted by that pair for deterministic test fixtures.
     */
    public static List<Pattern> enumerate(MultiGraphDB db) {
        Objects.requireNonNull(db, "db");

        // Key: pair of labels packed into a long (loLabel in high bits).
        // Value: accumulator with the canonical code and embedding list.
        Map<Long, Bucket> buckets = new HashMap<>();

        for (int gi = 0; gi < db.size(); gi++) {
            MultiGraph g = db.get(gi);
            for (int e = 0; e < g.numEdges(); e++) {
                int u = g.edgeSrc(e);
                int v = g.edgeDst(e);
                int lu = g.vertexLabel(u);
                int lv = g.vertexLabel(v);

                int lo, hi, hostLoVertex, hostHiVertex;
                if (lu <= lv) {
                    lo = lu;
                    hi = lv;
                    hostLoVertex = u;
                    hostHiVertex = v;
                } else {
                    lo = lv;
                    hi = lu;
                    hostLoVertex = v;
                    hostHiVertex = u;
                }

                long key = ((long) lo << 32) | (hi & 0xFFFFFFFFL);
                Bucket bucket = buckets.computeIfAbsent(key,
                    k -> new Bucket(lo, hi));

                int[] edgeMap = new int[]{e};

                // Primary orientation: pattern vertex 0 -> hostLoVertex, vertex 1 -> hostHiVertex.
                bucket.embeddings.add(
                    Embedding.of(gi, g, new int[]{hostLoVertex, hostHiVertex}, edgeMap));

                // When endpoint labels are identical, the reversed orientation is
                // also a valid embedding of the same pattern.
                if (lo == hi) {
                    bucket.embeddings.add(
                        Embedding.of(gi, g, new int[]{hostHiVertex, hostLoVertex}, edgeMap));
                }
            }
        }

        List<Pattern> patterns = new ArrayList<>(buckets.size());
        for (Bucket b : buckets.values()) {
            DFSCode code = DFSCode.of(new EdgeTuple(0, 1, b.loLabel, EDGE_LABEL_NONE, b.hiLabel));
            patterns.add(Pattern.of(code, b.embeddings));
        }

        patterns.sort(Comparator
            .comparingInt((Pattern p) -> p.code().edge(0).fromLabel)
            .thenComparingInt(p -> p.code().edge(0).toLabel));

        return patterns;
    }

    private static final class Bucket {
        final int loLabel;
        final int hiLabel;
        final List<Embedding> embeddings = new ArrayList<>();

        Bucket(int loLabel, int hiLabel) {
            this.loLabel = loLabel;
            this.hiLabel = hiLabel;
        }
    }
}
