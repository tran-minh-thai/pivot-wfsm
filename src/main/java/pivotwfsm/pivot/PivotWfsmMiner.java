package pivotwfsm.pivot;

import pivotwfsm.core.DFSCode;
import pivotwfsm.core.MinDFSCode;
import pivotwfsm.core.MultiGraph;
import pivotwfsm.core.MultiGraphDB;
import pivotwfsm.miner.ExtensionLookupTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pivot-WFSM mining loop under the bottleneck-MIN measure
 * (a host graph supports a pattern iff a pivot-anchored embedding exists whose
 * every edge weighs at least {@code tauW}).
 *
 * <p>The defining difference from {@link pivotwfsm.miner.EmbeddingStoreMiner}: NO embedding
 * store. Support is recomputed on demand by re-matching from the pivot inside
 * each candidate host graph. Everything else - F1 templates, right-most
 * extension, canonical checking, the optional structural+weight prefilter - is
 * shared with the baselines so a head-to-head isolates exactly the matching
 * strategy.
 */
public final class PivotWfsmMiner {

    public record MinedPattern(DFSCode code, int support, List<Integer> embedsGraphIds,
                               int pivotVertex) {}

    public record PhaseTimes(long indexNanos, long f1Nanos, long candidateGenNanos,
                             long canonicalNanos, long matchingNanos) {}

    public record Stats(long candidatesGenerated, long duplicatesSkipped,
                        long prefilterSkipped, long nonCanonicalSkipped,
                        long evaluated, long accepted, PhaseTimes phases) {}

    public record Result(List<MinedPattern> patterns, Stats stats) {}

    /** Whether to consult the structural+weight prefilter (ablation switch). */
    public enum PrefilterMode { ON, OFF }

    /** Which matching front-end to use (ablation switch). */
    public enum Matching { PIVOT, PLAIN }

    private PivotWfsmMiner() {
    }

    public static Result mine(MultiGraphDB db, int sigmaMin, double tauW) {
        return mine(db, sigmaMin, tauW, PrefilterMode.ON, Matching.PIVOT);
    }

    public static Result mine(MultiGraphDB db, int sigmaMin, double tauW,
                              PrefilterMode prefilterMode, Matching matching) {
        long t0 = System.nanoTime();
        HostGraphIndex[] index = new HostGraphIndex[db.size()];
        Map<Integer, HostGraphIndex> byId = new HashMap<>();
        for (int i = 0; i < db.size(); i++) {
            index[i] = HostGraphIndex.build(db.get(i));
            byId.put(db.get(i).id(), index[i]);
        }
        ExtensionLookupTable prefilter =
            prefilterMode == PrefilterMode.ON ? ExtensionLookupTable.build(db) : null;
        long indexNanos = System.nanoTime() - t0;

        long[] phase = new long[4]; // f1, candidateGen, canonical, matching
        long[] counters = new long[5]; // generated, dup, prefilter, nonCanon, evaluated

        // ---- F1: frequent 1-edge templates under the MIN measure ----
        long tf1 = System.nanoTime();
        Set<PivotExtensionGenerator.Template> frequentTemplates = new LinkedHashSet<>();
        Map<PivotExtensionGenerator.Template, List<Integer>> templateEmbeds =
            collectTemplateEmbeds(db, tauW);
        List<MinedPattern> currentLevel = new ArrayList<>();
        List<MinedPattern> results = new ArrayList<>();
        Set<DFSCode> seen = new HashSet<>();

        for (Map.Entry<PivotExtensionGenerator.Template, List<Integer>> e
                : templateEmbeds.entrySet()) {
            if (e.getValue().size() < sigmaMin) {
                continue;
            }
            PivotExtensionGenerator.Template t = e.getKey();
            frequentTemplates.add(t);
            DFSCode code = DFSCode.of(new DFSCode.EdgeTuple(
                0, 1, t.lowLabel(), t.edgeLabel(), t.highLabel()));
            PatternGraph pg = PatternGraph.fromCode(code);
            MinedPattern mined = new MinedPattern(code, e.getValue().size(),
                e.getValue(), PivotSelector.selectPivot(pg));
            currentLevel.add(mined);
            results.add(mined);
            seen.add(code);
        }
        phase[0] = System.nanoTime() - tf1;

        // ---- Level-wise expansion ----
        while (!currentLevel.isEmpty()) {
            List<MinedPattern> nextLevel = new ArrayList<>();

            for (MinedPattern parent : currentLevel) {
                PatternGraph parentGraph = PatternGraph.fromCode(parent.code());
                int parentPivot = PivotSelector.selectPivot(parentGraph);

                long tg = System.nanoTime();
                List<DFSCode> childCodes = PivotExtensionGenerator.childCodes(
                    parentGraph, parentPivot, frequentTemplates);
                phase[1] += System.nanoTime() - tg;

                for (DFSCode childCode : childCodes) {
                    counters[0]++;
                    if (!seen.add(childCode)) {
                        counters[1]++;
                        continue;
                    }

                    DFSCode.EdgeTuple last = childCode.edge(childCode.numEdges() - 1);
                    if (prefilter != null && !prefilter.canExtend(
                            last.fromLabel, last.toLabel, tauW, sigmaMin)) {
                        counters[2]++;
                        continue;
                    }

                    long tc = System.nanoTime();
                    boolean canonical = MinDFSCode.isMinimum(childCode);
                    phase[2] += System.nanoTime() - tc;
                    if (!canonical) {
                        counters[3]++;
                        continue;
                    }

                    PatternGraph child = PatternGraph.fromCode(childCode);
                    int pivot = PivotSelector.selectPivot(child);
                    PivotSignature signature = PivotSignature.build(child, pivot);

                    counters[4]++;
                    long tm = System.nanoTime();
                    List<Integer> embeds = scan(child, pivot, signature, byId,
                        parent.embedsGraphIds(), tauW, sigmaMin, matching);
                    phase[3] += System.nanoTime() - tm;

                    if (embeds != null) {
                        MinedPattern mined = new MinedPattern(
                            childCode, embeds.size(), embeds, pivot);
                        nextLevel.add(mined);
                        results.add(mined);
                    }
                }
            }
            currentLevel = nextLevel;
        }

        Stats stats = new Stats(counters[0], counters[1], counters[2], counters[3],
            counters[4], results.size(),
            new PhaseTimes(indexNanos, phase[0], phase[1], phase[2], phase[3]));
        return new Result(List.copyOf(results), stats);
    }

    /**
     * Re-match {@code child} in each graph of {@code scanSet} (the parent's
     * Embeds, anti-monotone). Returns the sorted supported-graph ids, or null
     * if the sigma early-stop proves the count cannot be reached.
     */
    private static List<Integer> scan(PatternGraph child, int pivot, PivotSignature signature,
                                      Map<Integer, HostGraphIndex> byId, List<Integer> scanSet,
                                      double tauW, int sigmaMin, Matching matching) {
        List<Integer> embeds = new ArrayList<>();
        int remaining = scanSet.size();
        for (int graphId : scanSet) {
            if (embeds.size() + remaining < sigmaMin) {
                return null;
            }
            remaining--;
            HostGraphIndex host = byId.get(graphId);
            boolean found;
            if (matching == Matching.PIVOT) {
                List<Integer> cands =
                    PivotCandidateFilter.findCandidatePivotNodes(signature, host);
                found = !cands.isEmpty()
                    && PivotMatcher.findAnyEmbedding(child, pivot, host, cands, tauW);
            } else {
                found = PivotMatcher.findAnyEmbedding(child, 0, host,
                    labelMatches(host, child.vertexLabel(0)), tauW);
            }
            if (found) {
                embeds.add(graphId);
            }
        }
        return embeds.size() >= sigmaMin ? embeds : null;
    }

    private static List<Integer> labelMatches(HostGraphIndex host, int label) {
        List<Integer> out = new ArrayList<>();
        for (int v = 0; v < host.vertexCount(); v++) {
            if (host.graph().vertexLabel(v) == label) {
                out.add(v);
            }
        }
        return out;
    }

    /** F1 templates with their supported-graph-id lists under the MIN floor. */
    private static Map<PivotExtensionGenerator.Template, List<Integer>> collectTemplateEmbeds(
            MultiGraphDB db, double tauW) {
        Map<PivotExtensionGenerator.Template, Set<Integer>> raw = new HashMap<>();
        for (int gi = 0; gi < db.size(); gi++) {
            MultiGraph g = db.get(gi);
            Set<PivotExtensionGenerator.Template> inThisGraph = new HashSet<>();
            for (int e = 0; e < g.numEdges(); e++) {
                if (g.edgeWeight(e) < tauW) {
                    continue;
                }
                int lu = g.vertexLabel(g.edgeSrc(e));
                int lv = g.vertexLabel(g.edgeDst(e));
                inThisGraph.add(new PivotExtensionGenerator.Template(
                    Math.min(lu, lv), 0, Math.max(lu, lv)));
            }
            for (PivotExtensionGenerator.Template t : inThisGraph) {
                raw.computeIfAbsent(t, k -> new java.util.TreeSet<>()).add(g.id());
            }
        }
        Map<PivotExtensionGenerator.Template, List<Integer>> out = new HashMap<>();
        for (Map.Entry<PivotExtensionGenerator.Template, Set<Integer>> e : raw.entrySet()) {
            out.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return out;
    }
}
