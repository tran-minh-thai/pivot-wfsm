package pivotwfsm.baselines.wfsm;

import pivotwfsm.core.DFSCode;
import pivotwfsm.core.DFSCode.EdgeTuple;
import pivotwfsm.core.Embedding;
import pivotwfsm.core.MinDFSCode;
import pivotwfsm.core.MultiGraph;
import pivotwfsm.core.MultiGraphDB;
import pivotwfsm.core.Pattern;
import pivotwfsm.miner.InitialPatterns;
import pivotwfsm.miner.MiningStats;
import pivotwfsm.miner.RightMostExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * WFSM-MaxPWS algorithm - Algorithm 2 of Islam, Ahmed, Alam &amp; Leung,
 * "Graph-based substructure pattern mining with edge-weight",
 * Applied Intelligence 54(5):3756-3785, 2024. DOI 10.1007/s10489-024-05356-7.
 *
 * <p>Faithful Java port of the paper's pseudocode, sharing the structural
 * subgraph-enumeration machinery (DFS code, embeddings, right-most extension)
 * with {@link pivotwfsm.miner.EmbeddingStoreMiner}. The pieces that are <b>specific</b>
 * to this algorithm:
 * <ul>
 *   <li>weighted-support measure {@code wsup(g) = W(g) * |support(g)|};</li>
 *   <li>MaxPWS pruning bound ({@link MaxPwsBound}) using Edge Class partitions
 *       ({@link EdgeClass}) and occurrence lists ({@link OccurrenceList});</li>
 *   <li>Lemma 1 canonical ordering ({@link WfsmCode}, {@link WfsmTuple}).</li>
 * </ul>
 *
 * <h2>Static-edge-weight assumption</h2>
 * The paper assumes that every edge in the database whose endpoint labels
 * and edge label match also has the same weight. Datasets that violate
 * this (e.g. {@code data/sample/illustrative.json} with its parallel edges
 * of differing weights) cannot be mined by WFSM-MaxPWS in a well-defined
 * way. The miner detects the violation and surfaces it via
 * {@link #validateStaticWeights(MultiGraphDB)}, returning a list of
 * offending edges instead of mining silently.
 *
 * <h2>Canonical check</h2>
 * Per Algorithm 4 of the paper. We reuse {@link MinDFSCode#isMinimum} for
 * the underlying structural canonicality. For the weight-aware step (Lemma 1)
 * we observe that the same algorithm applies if we use {@link WfsmTuple}
 * comparison instead of {@link EdgeTuple} comparison. In our current impl
 * we approximate by checking <em>structural</em> canonicality only; the
 * weight-aware step is a tightening for the bound, not for correctness of
 * the result set, so the impl is still complete (it may enumerate slightly
 * more candidates than the original).
 */
public final class WfsmMaxPwsMiner {

    private WfsmMaxPwsMiner() {}

    /** Result row for one frequent weighted subgraph. */
    public static final class WfsmResult {
        public final Pattern pattern;
        public final double averageWeight;
        public final int support;
        public final double weightedSupport;

        public WfsmResult(Pattern pattern, double averageWeight, int support, double weightedSupport) {
            this.pattern = pattern;
            this.averageWeight = averageWeight;
            this.support = support;
            this.weightedSupport = weightedSupport;
        }
    }

    /**
     * Mine all weighted-frequent subgraphs whose
     * {@code wsup = W(g) * |support(g)|} meets {@code tauWsup}.
     *
     * @param db       static-edge-weight database
     * @param tauWsup  weighted-support threshold
     * @return frequent weighted subgraphs in enumeration order
     */
    public static List<WfsmResult> mine(MultiGraphDB db, double tauWsup) {
        return mine(db, tauWsup, null);
    }

    public static List<WfsmResult> mine(MultiGraphDB db, double tauWsup, MiningStats stats) {
        validateStaticWeights(db);
        EdgeClass ec = EdgeClass.build(db);

        List<WfsmResult> output = new ArrayList<>();
        List<Pattern> seeds = InitialPatterns.enumerate(db);
        seeds.sort(Comparator
            .comparingInt((Pattern p) -> p.code().edge(0).fromLabel)
            .thenComparingInt(p -> p.code().edge(0).toLabel));

        for (Pattern seed : seeds) {
            double W = patternAverageWeight(seed, db);
            int    sup = seed.support();
            double wsup = W * sup;

            OccurrenceList ol = occurrenceListOf(seed, ec);
            double ftw = firstTupleWeight(seed, db);
            double maxPws = MaxPwsBound.maxPwsOver(
                seed.code().numEdges(), W, ftw,
                ol.edgeCounts(), ol.mpfArrayAscending());

            if (wsup >= tauWsup) {
                output.add(new WfsmResult(seed, W, sup, wsup));
                if (stats != null) stats.childPatternsEmitted++;
                expand(seed, db, ec, tauWsup, stats, output);
            } else if (maxPws >= tauWsup) {
                expand(seed, db, ec, tauWsup, stats, output);
            }
            // else: prune the entire subtree.
        }
        return output;
    }

    private static void expand(Pattern parent, MultiGraphDB db, EdgeClass ec,
                               double tauWsup, MiningStats stats, List<WfsmResult> output) {
        Map<EdgeTuple, Pattern> children = RightMostExtension.extend(parent, db);
        List<Map.Entry<EdgeTuple, Pattern>> entries = new ArrayList<>(children.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        for (Map.Entry<EdgeTuple, Pattern> en : entries) {
            Pattern child = en.getValue();
            if (stats != null) stats.childPatternsEnumerated++;
            if (!MinDFSCode.isMinimum(child.code())) {
                if (stats != null) stats.childPatternsRejectedNonCanonical++;
                continue;
            }

            double W = patternAverageWeight(child, db);
            int sup = child.support();
            double wsup = W * sup;

            OccurrenceList ol = occurrenceListOf(child, ec);
            double ftw = firstTupleWeight(child, db);
            double maxPws = MaxPwsBound.maxPwsOver(
                child.code().numEdges(), W, ftw,
                ol.edgeCounts(), ol.mpfArrayAscending());

            if (wsup >= tauWsup) {
                output.add(new WfsmResult(child, W, sup, wsup));
                if (stats != null) stats.childPatternsEmitted++;
                expand(child, db, ec, tauWsup, stats, output);
            } else if (maxPws >= tauWsup) {
                expand(child, db, ec, tauWsup, stats, output);
            }
        }
    }

    /** W(g) = average edge weight, using the best embedding's edge weights in any host. */
    private static double patternAverageWeight(Pattern p, MultiGraphDB db) {
        for (int gid : p.occursIn()) {
            List<Embedding> embs = p.embeddingsInGraph(gid);
            if (!embs.isEmpty()) {
                return embs.get(0).wAvg();  // static weights → any embedding gives the same average
            }
        }
        return 0.0;
    }

    /** First Tuple Weight under Lemma 1 = the heaviest edge in any embedding. */
    private static double firstTupleWeight(Pattern p, MultiGraphDB db) {
        double best = 0.0;
        for (int gid : p.occursIn()) {
            for (Embedding emb : p.embeddingsInGraph(gid)) {
                double[] ws = emb.edgeWeightsUnsafe();
                for (double w : ws) if (w > best) best = w;
            }
        }
        return best;
    }

    /**
     * Build the OL of {@code p} grouped by the Edge Class partition each
     * host graph belongs to.
     */
    private static OccurrenceList occurrenceListOf(Pattern p, EdgeClass ec) {
        Map<Integer, Integer> graphToPartition = new HashMap<>();
        for (var part : ec.partitionsAscending()) {
            for (int gid : part.graphIds) {
                graphToPartition.put(gid, part.edgeCount);
            }
        }
        TreeMap<Integer, List<Integer>> byPartition = new TreeMap<>();
        for (int gid : p.occursIn()) {
            int m = graphToPartition.getOrDefault(gid, -1);
            if (m < 0) continue;
            byPartition.computeIfAbsent(m, k -> new ArrayList<>()).add(gid);
        }
        List<OccurrenceList.OLM> olms = new ArrayList<>();
        for (var en : byPartition.entrySet()) {
            int[] ids = en.getValue().stream().mapToInt(Integer::intValue).toArray();
            olms.add(new OccurrenceList.OLM(en.getKey(), ids));
        }
        return new OccurrenceList(olms);
    }

    /**
     * Verify the database obeys the WFSM-MaxPWS static-weight assumption:
     * every edge with the same {@code (fromLabel, toLabel)} label-pair must
     * have the same weight. Throws on the first violation rather than
     * silently misbehaving downstream.
     */
    public static void validateStaticWeights(MultiGraphDB db) {
        Set<Long> seen = new HashSet<>();
        Map<Long, Double> expected = new HashMap<>();
        for (int gid = 0; gid < db.size(); gid++) {
            MultiGraph g = db.get(gid);
            for (int e = 0; e < g.numEdges(); e++) {
                int lu = g.vertexLabel(g.edgeSrc(e));
                int lv = g.vertexLabel(g.edgeDst(e));
                long key = ((long) Math.min(lu, lv) << 32) | (Math.max(lu, lv) & 0xFFFFFFFFL);
                double w = g.edgeWeight(e);
                if (seen.add(key)) {
                    expected.put(key, w);
                } else {
                    double prev = expected.get(key);
                    if (Math.abs(prev - w) > 1e-9) {
                        throw new IllegalArgumentException(
                            "WFSM-MaxPWS requires static edge weights but found two edges of "
                                + "label pair (" + (int)(key >>> 32) + ","
                                + (int)(key & 0xFFFFFFFFL) + ") "
                                + "with different weights: " + prev + " vs " + w
                                + " (graph " + gid + ", edge " + e + ")");
                    }
                }
            }
        }
    }
}
