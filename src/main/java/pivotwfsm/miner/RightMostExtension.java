package pivotwfsm.miner;

import pivotwfsm.core.DFSCode;
import pivotwfsm.core.DFSCode.EdgeTuple;
import pivotwfsm.core.Embedding;
import pivotwfsm.core.MultiGraph;
import pivotwfsm.core.MultiGraphDB;
import pivotwfsm.core.Pattern;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * gSpan right-most extension. Given a {@link Pattern} {@code parent},
 * generates every child pattern obtained by adding exactly one edge:
 *
 * <ul>
 *   <li><b>Forward extension</b>: from any vertex on the right-most path to
 *       a brand-new vertex (DFS index = {@code parent.numVertices()}).</li>
 *   <li><b>Backward extension</b>: from the right-most vertex of the
 *       pattern back to another vertex already on the right-most path,
 *       closing a cycle. (Parallel edges in the pattern are allowed - the
 *       enumeration treats them just like any other extension.)</li>
 * </ul>
 *
 * <p>Embeddings are recomputed per child: each one inherits the parent's
 * vertex/edge map and appends the new host vertex / host edge being used.
 * Two host edges that differ only in their edge index (parallel edges)
 * produce two distinct child embeddings sharing the same edge tuple.
 *
 * <p>The four-argument overload accepts an {@link ExtensionLookupTable}
 * <i>prefilter</i>: before doing any embedding-level work for a candidate
 * label pair, the prefilter answers in {@code O(1)} whether the pair could
 * possibly support a frequent child. Mismatched pairs are short-circuited
 * out entirely. This is the unified structural+weight prefilter.
 */
public final class RightMostExtension {

    private RightMostExtension() {}

    /** Convenience overload: no prefilter, no statistics. */
    public static Map<EdgeTuple, Pattern> extend(Pattern parent, MultiGraphDB db) {
        return extend(parent, db, null, 0.0, 0, null);
    }

    /** Full extension with optional prefilter, enumerate mode (no decomposition). */
    public static Map<EdgeTuple, Pattern> extend(
            Pattern parent, MultiGraphDB db,
            ExtensionLookupTable prefilter, double tauW, int sigmaMin,
            MiningStats stats) {
        return extend(parent, db, prefilter, tauW, sigmaMin, stats, false);
    }

    /**
     * Full extension with optional unified prefilter and optional parallel-edge
     * decomposition.
     *
     * <p>When {@code decompose} is {@code true}, parallel host edges between the
     * same ordered vertex pair are collapsed to their single maximum-weight
     * representative, so a fixed vertex mapping yields exactly one child
     * embedding instead of one per parallel edge. Under the bottleneck-MIN
     * aggregator this is exact - the optimal embedding takes, per pattern edge,
     * the strongest parallel host edge - and it removes the multiplicative
     * embedding blow-up that parallel edges otherwise cause. It is NOT valid for
     * sum/average measures, so the baselines (which pass {@code decompose=false}
     * via the shorter overloads) never use it.
     *
     * @param prefilter  if non-null, used to skip label pairs that cannot
     *                   contribute {@code sigmaMin} frequent host graphs
     * @param tauW       weight threshold consulted by the prefilter
     * @param sigmaMin   support threshold consulted by the prefilter
     * @param stats      optional counter sink; pass {@code null} to disable
     * @param decompose  collapse parallel edges to their max-weight representative
     */
    public static Map<EdgeTuple, Pattern> extend(
            Pattern parent, MultiGraphDB db,
            ExtensionLookupTable prefilter, double tauW, int sigmaMin,
            MiningStats stats, boolean decompose) {

        DFSCode parentCode = parent.code();
        if (parentCode.numEdges() == 0) {
            throw new IllegalArgumentException(
                "cannot extend the empty pattern via right-most extension; "
                    + "use InitialPatterns.enumerate(db) for the 1-edge seeds first");
        }

        int[] rmPath = parentCode.rightMostPath();
        int[] parentLabels = parentCode.vertexLabels();
        int rightMostPatVertex = parentCode.numVertices() - 1;
        int newPatVertexIndex = parentCode.numVertices();

        // Per-call cache of prefilter decisions, keyed by packed (anchorLabel, otherLabel).
        // The cache is local because the (sigmaMin, tauW) thresholds are fixed for the call,
        // and the same label pair often shows up across many embeddings.
        Map<Long, Boolean> aliveCache = new HashMap<>();

        Map<EdgeTuple, List<Embedding>> bucket = new HashMap<>();

        for (int gid : parent.occursIn()) {
            MultiGraph host = db.get(gid);

            for (Embedding emb : parent.embeddingsInGraph(gid)) {
                int[] vmap = emb.vertexMapCopy();
                int[] emap = emb.edgeMapCopy();

                Set<Integer> usedHostV = new HashSet<>();
                for (int v : vmap) usedHostV.add(v);
                Set<Integer> usedHostE = new HashSet<>();
                for (int e : emap) usedHostE.add(e);

                // 1. BACKWARD extensions: rightmost pattern vertex -> vertex on rmPath
                //    (any vertex on rmPath EXCEPT the rightmost itself).
                int rmHostV = vmap[rightMostPatVertex];
                for (int e = 0; !decompose && e < host.numEdges(); e++) {
                    if (usedHostE.contains(e)) continue;
                    int otherHostV = otherEndpoint(host, e, rmHostV);
                    if (otherHostV < 0) continue;

                    for (int p = 0; p < rmPath.length - 1; p++) {
                        int patV = rmPath[p];
                        if (vmap[patV] != otherHostV) continue;

                        int anchorLabel = parentLabels[rightMostPatVertex];
                        int otherLabel = parentLabels[patV];

                        if (stats != null) stats.embeddingExtensionsConsidered++;

                        if (!aliveByPrefilter(aliveCache, prefilter, anchorLabel, otherLabel,
                                              tauW, sigmaMin, stats)) {
                            break;
                        }

                        EdgeTuple tuple = new EdgeTuple(
                            rightMostPatVertex, patV,
                            anchorLabel, InitialPatterns.EDGE_LABEL_NONE, otherLabel);
                        int[] newEMap = append(emap, e);
                        Embedding newEmb = Embedding.of(gid, host, vmap, newEMap);
                        bucket.computeIfAbsent(tuple, k -> new ArrayList<>()).add(newEmb);
                        if (stats != null) stats.embeddingExtensionsMaterialised++;
                        break;
                    }
                }
                // 1'. BACKWARD, decompose mode: one max-weight representative per
                //     existing target vertex on the right-most path.
                for (int p = 0; decompose && p < rmPath.length - 1; p++) {
                    int patV = rmPath[p];
                    int targetHostV = vmap[patV];
                    int bestE = -1;
                    double bestW = Double.NEGATIVE_INFINITY;
                    for (int e = 0; e < host.numEdges(); e++) {
                        if (usedHostE.contains(e)) continue;
                        if (otherEndpoint(host, e, rmHostV) != targetHostV) continue;
                        double w = host.edgeWeight(e);
                        if (w > bestW) { bestW = w; bestE = e; }
                    }
                    if (bestE < 0) continue;

                    int anchorLabel = parentLabels[rightMostPatVertex];
                    int otherLabel = parentLabels[patV];
                    if (stats != null) stats.embeddingExtensionsConsidered++;
                    if (!aliveByPrefilter(aliveCache, prefilter, anchorLabel, otherLabel,
                                          tauW, sigmaMin, stats)) {
                        continue;
                    }
                    EdgeTuple tuple = new EdgeTuple(
                        rightMostPatVertex, patV,
                        anchorLabel, InitialPatterns.EDGE_LABEL_NONE, otherLabel);
                    int[] newEMap = append(emap, bestE);
                    Embedding newEmb = Embedding.of(gid, host, vmap, newEMap);
                    bucket.computeIfAbsent(tuple, k -> new ArrayList<>()).add(newEmb);
                    if (stats != null) stats.embeddingExtensionsMaterialised++;
                }

                // 2. FORWARD extensions: from any rmPath vertex to a NEW host vertex.
                for (int p = 0; p < rmPath.length; p++) {
                    int patV = rmPath[p];
                    int anchorHostV = vmap[patV];

                    for (int e = 0; !decompose && e < host.numEdges(); e++) {
                        if (usedHostE.contains(e)) continue;
                        int otherHostV = otherEndpoint(host, e, anchorHostV);
                        if (otherHostV < 0) continue;
                        if (usedHostV.contains(otherHostV)) continue;

                        int anchorLabel = parentLabels[patV];
                        int newLabel = host.vertexLabel(otherHostV);

                        if (stats != null) stats.embeddingExtensionsConsidered++;

                        if (!aliveByPrefilter(aliveCache, prefilter, anchorLabel, newLabel,
                                              tauW, sigmaMin, stats)) {
                            continue;
                        }

                        EdgeTuple tuple = new EdgeTuple(
                            patV, newPatVertexIndex,
                            anchorLabel, InitialPatterns.EDGE_LABEL_NONE, newLabel);
                        int[] newVMap = append(vmap, otherHostV);
                        int[] newEMap = append(emap, e);
                        Embedding newEmb = Embedding.of(gid, host, newVMap, newEMap);
                        bucket.computeIfAbsent(tuple, k -> new ArrayList<>()).add(newEmb);
                        if (stats != null) stats.embeddingExtensionsMaterialised++;
                    }

                    // FORWARD, decompose mode: one max-weight representative per
                    // distinct new host vertex (collapses parallel edges).
                    if (decompose) {
                        Map<Integer, Integer> bestEdge = new HashMap<>();
                        Map<Integer, Double> bestWt = new HashMap<>();
                        for (int e = 0; e < host.numEdges(); e++) {
                            if (usedHostE.contains(e)) continue;
                            int otherHostV = otherEndpoint(host, e, anchorHostV);
                            if (otherHostV < 0) continue;
                            if (usedHostV.contains(otherHostV)) continue;
                            double w = host.edgeWeight(e);
                            Double cur = bestWt.get(otherHostV);
                            if (cur == null || w > cur) {
                                bestWt.put(otherHostV, w);
                                bestEdge.put(otherHostV, e);
                            }
                        }
                        for (Map.Entry<Integer, Integer> en : bestEdge.entrySet()) {
                            int otherHostV = en.getKey();
                            int e = en.getValue();
                            int anchorLabel = parentLabels[patV];
                            int newLabel = host.vertexLabel(otherHostV);

                            if (stats != null) stats.embeddingExtensionsConsidered++;
                            if (!aliveByPrefilter(aliveCache, prefilter, anchorLabel, newLabel,
                                                  tauW, sigmaMin, stats)) {
                                continue;
                            }
                            EdgeTuple tuple = new EdgeTuple(
                                patV, newPatVertexIndex,
                                anchorLabel, InitialPatterns.EDGE_LABEL_NONE, newLabel);
                            int[] newVMap = append(vmap, otherHostV);
                            int[] newEMap = append(emap, e);
                            Embedding newEmb = Embedding.of(gid, host, newVMap, newEMap);
                            bucket.computeIfAbsent(tuple, k -> new ArrayList<>()).add(newEmb);
                            if (stats != null) stats.embeddingExtensionsMaterialised++;
                        }
                    }
                }
            }
        }

        Map<EdgeTuple, Pattern> result = new HashMap<>();
        for (Map.Entry<EdgeTuple, List<Embedding>> en : bucket.entrySet()) {
            DFSCode childCode = parentCode.extend(en.getKey());
            result.put(en.getKey(), Pattern.of(childCode, en.getValue()));
        }
        return result;
    }

    /**
     * Cached prefilter probe. Returns true if the label pair survives the
     * prefilter (or if no prefilter is configured). Increments the
     * {@code extensionTuplesPrefilterSkipped} counter the FIRST time a
     * label pair is found dead.
     */
    private static boolean aliveByPrefilter(Map<Long, Boolean> cache,
                                            ExtensionLookupTable prefilter,
                                            int anchorLabel, int otherLabel,
                                            double tauW, int sigmaMin,
                                            MiningStats stats) {
        if (prefilter == null) return true;
        long key = ((long) Math.min(anchorLabel, otherLabel) << 32)
                  | (Math.max(anchorLabel, otherLabel) & 0xFFFFFFFFL);
        Boolean cached = cache.get(key);
        if (cached != null) return cached;
        boolean alive = prefilter.canExtend(anchorLabel, otherLabel, tauW, sigmaMin);
        cache.put(key, alive);
        if (!alive && stats != null) stats.extensionTuplesPrefilterSkipped++;
        return alive;
    }

    /** Other endpoint of host edge {@code e}, or -1 if {@code v} is not on it. */
    private static int otherEndpoint(MultiGraph host, int e, int v) {
        int s = host.edgeSrc(e);
        int d = host.edgeDst(e);
        if (s == v) return d;
        if (d == v) return s;
        return -1;
    }

    private static int[] append(int[] arr, int val) {
        int[] out = Arrays.copyOf(arr, arr.length + 1);
        out[arr.length] = val;
        return out;
    }
}
