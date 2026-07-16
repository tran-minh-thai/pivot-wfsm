package pivotwfsm.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A weighted subgraph pattern: its canonical {@link DFSCode} plus the
 * embeddings discovered in each host graph of a {@link MultiGraphDB}.
 *
 * <p>Pattern equality is determined by the DFS code alone, since the code is
 * the canonical identifier in gSpan. Two Patterns with the same code but
 * different embedding lists describe the same pattern in two different
 * databases.
 *
 * <p>The aggregator API ({@link #supportMin(double)}, {@link #supportAvg(double)})
 * implements the project's notion of <em>weighted transactional support</em>:
 * a host graph counts toward support iff some embedding of the pattern in
 * that graph has the chosen aggregate weight at or above {@code tauW}. For
 * MIN this is the best (highest) bottleneck across embeddings; for AVG the
 * best (highest) mean. Picking the best embedding per host is consistent
 * with WFSM-MaxPWS and DewgSpan.
 */
public final class Pattern {

    private final DFSCode code;
    private final Map<Integer, List<Embedding>> embeddingsByGraph;

    private Pattern(DFSCode code, Map<Integer, List<Embedding>> embeddings) {
        this.code = code;
        this.embeddingsByGraph = embeddings;
    }

    public static Pattern of(DFSCode code, List<Embedding> embeddings) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(embeddings, "embeddings");
        Map<Integer, List<Embedding>> byGraph = new HashMap<>();
        for (Embedding emb : embeddings) {
            if (emb.patternEdgeCount() != code.numEdges()) {
                throw new IllegalArgumentException(
                    "embedding has " + emb.patternEdgeCount()
                        + " edges, pattern has " + code.numEdges());
            }
            byGraph.computeIfAbsent(emb.hostGraphId(), k -> new ArrayList<>()).add(emb);
        }
        return new Pattern(code, byGraph);
    }

    public DFSCode code() {
        return code;
    }

    /** Host graph IDs in which the pattern occurs (i.e. has at least one embedding). */
    public List<Integer> occursIn() {
        List<Integer> ids = new ArrayList<>(embeddingsByGraph.keySet());
        Collections.sort(ids);
        return ids;
    }

    public List<Embedding> embeddingsInGraph(int graphId) {
        return embeddingsByGraph.getOrDefault(graphId, Collections.emptyList());
    }

    /**
     * Transactional support: number of host graphs containing the pattern.
     * This is the support measure used by the unweighted miner; the
     * weighted versions are {@link #supportMin(double)} and
     * {@link #supportAvg(double)}.
     */
    public int support() {
        return embeddingsByGraph.size();
    }

    /**
     * Weighted transactional support under the MIN aggregator. A host graph
     * contributes iff its <em>best</em> embedding satisfies {@code W_min >= tauW}.
     */
    public int supportMin(double tauW) {
        int s = 0;
        for (List<Embedding> embs : embeddingsByGraph.values()) {
            for (Embedding e : embs) {
                if (e.wMin() >= tauW) {
                    s++;
                    break;
                }
            }
        }
        return s;
    }

    /**
     * Weighted transactional support under the AVG aggregator. Same shape
     * as {@link #supportMin(double)} but using {@link Embedding#wAvg()}.
     */
    public int supportAvg(double tauW) {
        int s = 0;
        for (List<Embedding> embs : embeddingsByGraph.values()) {
            for (Embedding e : embs) {
                if (e.wAvg() >= tauW) {
                    s++;
                    break;
                }
            }
        }
        return s;
    }

    /**
     * Weighted transactional support under an arbitrary {@link pivotwfsm.weight.Aggregator}.
     * A host graph contributes iff its best embedding satisfies
     * {@code agg.aggregate(edgeWeights) >= tauW}. Enables ablation studies
     * that swap aggregators without forking the miner.
     */
    public int supportUnder(pivotwfsm.weight.Aggregator agg, double tauW) {
        java.util.Objects.requireNonNull(agg, "agg");
        int s = 0;
        for (List<Embedding> embs : embeddingsByGraph.values()) {
            for (Embedding e : embs) {
                if (agg.aggregate(e.edgeWeightsUnsafe()) >= tauW) {
                    s++;
                    break;
                }
            }
        }
        return s;
    }

    /** Best (highest) bottleneck across embeddings in the given host. */
    public double bestWMin(int graphId) {
        double best = Double.NEGATIVE_INFINITY;
        for (Embedding e : embeddingsInGraph(graphId)) {
            if (e.wMin() > best) best = e.wMin();
        }
        return best;
    }

    /** Best (highest) mean weight across embeddings in the given host. */
    public double bestWAvg(int graphId) {
        double best = Double.NEGATIVE_INFINITY;
        for (Embedding e : embeddingsInGraph(graphId)) {
            if (e.wAvg() > best) best = e.wAvg();
        }
        return best;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Pattern p)) return false;
        return code.equals(p.code);
    }

    @Override
    public int hashCode() {
        return code.hashCode();
    }

    @Override
    public String toString() {
        return "Pattern{" + code + ", |graphs|=" + embeddingsByGraph.size() + "}";
    }
}
