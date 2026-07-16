package pivotwfsm.miner;

import pivotwfsm.core.DFSCode.EdgeTuple;
import pivotwfsm.core.MinDFSCode;
import pivotwfsm.core.MultiGraphDB;
import pivotwfsm.core.Pattern;
import pivotwfsm.weight.Aggregator;
import pivotwfsm.weight.AvgAggregator;
import pivotwfsm.weight.MinAggregator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The embedding-store baseline: a gSpan-style DFS search tree over weighted
 * subgraph patterns that keeps, for every live pattern, the full list of its
 * embeddings. This is the method Pivot-WFSM is compared against; the two differ
 * only in how a candidate is evaluated (see {@link pivotwfsm.pivot.PivotWfsmMiner}).
 *
 * <p>It is implemented inside this repository, rather than taken from an
 * external tool, so that both sides of the comparison share one graph
 * representation, one weight measure and one canonicity checker. That is what
 * makes the comparison isolate the matching strategy and nothing else.
 *
 * <p>Skeleton of the search:
 * <pre>
 *   for each 1-edge seed s (from {@link InitialPatterns}):
 *       if emit(s):  output += s
 *       if expand(s): recurse(s)
 *
 *   recurse(parent):
 *       for each child of parent (from {@link RightMostExtension}):
 *           if not isMinimum(child.code):  continue          // dedup
 *           if emit(child):   output += child
 *           if expand(child): recurse(child)
 * </pre>
 *
 * <p><b>Pruning regime</b> - three filters stacked, each operating earlier
 * than the next:
 * <ol>
 *   <li>Unified <b>structural+weight prefilter</b>
 *       ({@link ExtensionLookupTable}). O(1) per
 *       label-pair lookup, applied <em>before</em> any embedding-level work.
 *       Optional via the {@code prefilter} parameter.</li>
 *   <li><b>Anti-monotone weight</b> - for an aggregator with
 *       {@link Aggregator#isAntiMonotone()} = true (e.g. MIN), prune a subtree
 *       whenever {@code sigma_AGG(P) &lt; sigma_min}. For non-anti-monotone
 *       aggregators (AVG) the prune falls back to transactional support.</li>
 *   <li><b>Canonical-code deduplication</b> ({@link MinDFSCode}) - drop
 *       any child whose DFS code is not its own lex-min.</li>
 * </ol>
 *
 * <p>The first filter is a structural pre-filter, the second prunes on the
 * weight measure, and the third is gSpan's standard duplicate-suppression
 * mechanism. All three are shared with Pivot-WFSM, which is why the comparison
 * between the two isolates the matching strategy and nothing else.
 */
public final class EmbeddingStoreMiner {

    /**
     * Convenience facade for the two aggregators the paper focuses on.
     * Equivalent to passing the corresponding {@link Aggregator} instance
     * via {@link #mine(MultiGraphDB, int, double, Aggregator)}.
     */
    public enum AggregatorMode {
        /** Bottleneck-MIN. Anti-monotone naturally; supports tight pruning. */
        MIN(new MinAggregator()),
        /** Average. Not anti-monotone; pruning is by transactional support only. */
        AVG(new AvgAggregator());

        private final Aggregator agg;
        AggregatorMode(Aggregator agg) { this.agg = agg; }
        public Aggregator aggregator() { return agg; }
    }

    private EmbeddingStoreMiner() {}

    /** Mine using the named aggregator mode, no prefilter. Delegates. */
    public static List<Pattern> mine(MultiGraphDB db, int sigmaMin, double tauW, AggregatorMode mode) {
        Objects.requireNonNull(mode, "mode");
        return mine(db, sigmaMin, tauW, mode.aggregator(), null, null);
    }

    /** Mine using the given aggregator, no prefilter. */
    public static List<Pattern> mine(MultiGraphDB db, int sigmaMin, double tauW, Aggregator aggregator) {
        return mine(db, sigmaMin, tauW, aggregator, null, null);
    }

    /**
     * Mine with the unified structural+weight prefilter enabled. This is the
     * strongest configuration of the baseline, and the one used for the headline
     * comparisons.
     *
     * <p>Builds an {@link ExtensionLookupTable} from the database once, then
     * passes it to the inner mining loop. Use this for production runs;
     * use {@link #mine(MultiGraphDB, int, double, Aggregator)} or pass
     * {@code prefilter=null} for the no-prefilter ablation variant.
     */
    public static List<Pattern> mineWithPrefilter(
            MultiGraphDB db, int sigmaMin, double tauW, Aggregator aggregator) {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(aggregator, "aggregator");
        ExtensionLookupTable prefilter = ExtensionLookupTable.build(db);
        return mine(db, sigmaMin, tauW, aggregator, prefilter, null);
    }

    /**
     * Legacy threshold for {@link ExtensionLookupTable#estimatedKillRate}.
     * Kept exposed so prefilter diagnostics can reference the earlier value,
     * but {@link #mineAdaptive} no longer consults it - see that method's
     * Javadoc for the predictor actually used.
     */
    public static final double PREFILTER_ACTIVATION_THRESHOLD = 0.10;

    /**
     * Adaptive variant: builds the prefilter and decides whether to
     * activate it based on whether the input looks like a static or
     * per-instance weighted database.
     *
     * <p>The earlier kill-rate heuristic (count of label-pairs whose
     * surviving graphs fall below {@code sigmaMin}) was discarded because
     * empirically both schemas have kill rates of {@code 0.6}-{@code 1.0}
     * on TUDataset, so it cannot tell them apart. We instead probe
     * weight uniformity directly via
     * {@link ExtensionLookupTable#looksStaticSchema()}: on static inputs
     * the per-graph max edge weight per label-pair is constant, on
     * per-instance inputs it varies.
     *
     * <p>Measured result motivating this rule: the prefilter contributes
     * 1.1-1.9x speedup on static schemas (where many label-pairs are
     * structurally exhausted), but adds 5-15% overhead on per-instance
     * schemas (where individual-edge variance defeats the per-label
     * upper bound). Bypassing the lookup entirely when the input is
     * per-instance recovers that overhead.
     */
    public static List<Pattern> mineAdaptive(
            MultiGraphDB db, int sigmaMin, double tauW, Aggregator aggregator) {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(aggregator, "aggregator");
        ExtensionLookupTable prefilter = ExtensionLookupTable.build(db);
        ExtensionLookupTable effective = prefilter.looksStaticSchema() ? prefilter : null;
        return mine(db, sigmaMin, tauW, aggregator, effective, null);
    }

    /**
     * Full mining API.
     *
     * @param db          weighted multigraph database
     * @param sigmaMin    minimum weighted support (integer count; 1..|DB|)
     * @param tauW        weight threshold; a host graph contributes only if
     *                    its best embedding meets this floor under {@code aggregator}
     * @param aggregator  weight aggregator strategy
     * @param prefilter   optional unified prefilter; {@code null} disables it
     * @param stats       optional counter sink; {@code null} disables instrumentation
     * @return all frequent canonical patterns in DFS-tree order
     */
    public static List<Pattern> mine(
            MultiGraphDB db, int sigmaMin, double tauW, Aggregator aggregator,
            ExtensionLookupTable prefilter, MiningStats stats) {
        return mine(db, sigmaMin, tauW, aggregator, prefilter, stats, false);
    }

    /**
     * Full mining API with the parallel-edge decomposition toggle.
     *
     * @param decompose when {@code true}, parallel host edges between the same
     *                  vertex pair are collapsed to their max-weight
     *                  representative during extension. Exact under
     *                  bottleneck-MIN and removes the {@code k^m} embedding
     *                  blow-up on multigraphs; leave {@code false} for the
     *                  enumerate-all-embeddings ablation baseline.
     */
    public static List<Pattern> mine(
            MultiGraphDB db, int sigmaMin, double tauW, Aggregator aggregator,
            ExtensionLookupTable prefilter, MiningStats stats, boolean decompose) {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(aggregator, "aggregator");

        List<Pattern> output = new ArrayList<>();

        List<Pattern> seeds = InitialPatterns.enumerate(db);
        seeds.sort(Comparator
            .comparingInt((Pattern p) -> p.code().edge(0).fromLabel)
            .thenComparingInt(p -> p.code().edge(0).toLabel));

        for (Pattern seed : seeds) {
            if (shouldEmit(seed, sigmaMin, tauW, aggregator)) {
                output.add(seed);
                if (stats != null) stats.childPatternsEmitted++;
            }
            if (shouldExpand(seed, sigmaMin, tauW, aggregator)) {
                expand(seed, db, sigmaMin, tauW, aggregator, prefilter, stats, output, decompose);
            }
        }
        return output;
    }

    private static void expand(Pattern parent, MultiGraphDB db,
                               int sigmaMin, double tauW, Aggregator agg,
                               ExtensionLookupTable prefilter, MiningStats stats,
                               List<Pattern> output, boolean decompose) {
        Map<EdgeTuple, Pattern> children = RightMostExtension.extend(
            parent, db, prefilter, tauW, sigmaMin, stats, decompose);

        // Deterministic order: sort by the extension tuple's lex order.
        List<Map.Entry<EdgeTuple, Pattern>> entries = new ArrayList<>(children.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        for (Map.Entry<EdgeTuple, Pattern> en : entries) {
            Pattern child = en.getValue();
            if (stats != null) stats.childPatternsEnumerated++;

            if (!MinDFSCode.isMinimum(child.code())) {
                if (stats != null) stats.childPatternsRejectedNonCanonical++;
                continue;
            }

            if (shouldEmit(child, sigmaMin, tauW, agg)) {
                output.add(child);
                if (stats != null) stats.childPatternsEmitted++;
            }
            if (shouldExpand(child, sigmaMin, tauW, agg)) {
                expand(child, db, sigmaMin, tauW, agg, prefilter, stats, output, decompose);
            }
        }
    }

    /** Output rule: pattern is reported iff its weighted support meets the floor. */
    private static boolean shouldEmit(Pattern p, int sigmaMin, double tauW, Aggregator agg) {
        return p.supportUnder(agg, tauW) >= sigmaMin;
    }

    /**
     * Recursion rule. For anti-monotone aggregators this is the same as the
     * emit rule (tight pruning). For non-anti-monotone aggregators it falls
     * back to transactional support - the looser of the two - because
     * {@code sigma_AGG} itself is not anti-monotone.
     */
    private static boolean shouldExpand(Pattern p, int sigmaMin, double tauW, Aggregator agg) {
        if (agg.isAntiMonotone()) {
            return p.supportUnder(agg, tauW) >= sigmaMin;
        }
        return p.support() >= sigmaMin;
    }
}
