package pivotwfsm.baselines.wfsm;

import pivotwfsm.core.DFSCode.EdgeTuple;
import pivotwfsm.core.Embedding;
import pivotwfsm.core.MinDFSCode;
import pivotwfsm.core.MultiGraphDB;
import pivotwfsm.core.Pattern;
import pivotwfsm.miner.InitialPatterns;
import pivotwfsm.miner.MiningStats;
import pivotwfsm.miner.RightMostExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * DewgSpan algorithm (Islam et al., Applied Intelligence 54(5):3756–3785,
 * 2024; DOI 10.1007/s10489-024-05356-7, §5). The dynamic-edge-weight
 * extension of WFSM-MaxPWS.
 *
 * <p>DewgSpan uses a {@link WeightDistributionTable} to estimate the
 * heaviest possible extension weight when edge weights of the same label
 * triple may differ across instances. On a static-weight dataset (the case the
 * published WFSM baselines assume) the WDT collapses to one weight per triple,
 * and DewgSpan's bound coincides with WFSM-MaxPWS's FTW for that triple.
 * Its presence as a baseline shows the WDT overhead does not dominate the
 * runtime even when the dynamic-weight capability is not exercised.
 *
 * <p>Unlike WFSM-MaxPWS we do <b>not</b> enforce the static-weight
 * validation here - DewgSpan accepts dynamic weights by construction.
 */
public final class DewgSpanMiner {

    private DewgSpanMiner() {}

    public static List<WfsmMaxPwsMiner.WfsmResult> mine(MultiGraphDB db, double tauWsup) {
        return mine(db, tauWsup, null);
    }

    public static List<WfsmMaxPwsMiner.WfsmResult> mine(MultiGraphDB db, double tauWsup,
                                                         MiningStats stats) {
        EdgeClass ec = EdgeClass.build(db);
        WeightDistributionTable wdt = WeightDistributionTable.build(db);

        List<WfsmMaxPwsMiner.WfsmResult> output = new ArrayList<>();
        List<Pattern> seeds = InitialPatterns.enumerate(db);
        seeds.sort(Comparator
            .comparingInt((Pattern p) -> p.code().edge(0).fromLabel)
            .thenComparingInt(p -> p.code().edge(0).toLabel));

        for (Pattern seed : seeds) {
            double W = patternAverageWeight(seed);
            int sup = seed.support();
            double wsup = W * sup;

            OccurrenceList ol = occurrenceListOf(seed, ec);
            double maxPws = dewgMaxPws(seed, W, ol, wdt);

            if (wsup >= tauWsup) {
                output.add(new WfsmMaxPwsMiner.WfsmResult(seed, W, sup, wsup));
                if (stats != null) stats.childPatternsEmitted++;
                expand(seed, db, ec, wdt, tauWsup, stats, output);
            } else if (maxPws >= tauWsup) {
                expand(seed, db, ec, wdt, tauWsup, stats, output);
            }
        }
        return output;
    }

    private static void expand(Pattern parent, MultiGraphDB db, EdgeClass ec,
                               WeightDistributionTable wdt, double tauWsup,
                               MiningStats stats, List<WfsmMaxPwsMiner.WfsmResult> output) {
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

            double W = patternAverageWeight(child);
            int sup = child.support();
            double wsup = W * sup;

            OccurrenceList ol = occurrenceListOf(child, ec);
            double maxPws = dewgMaxPws(child, W, ol, wdt);

            if (wsup >= tauWsup) {
                output.add(new WfsmMaxPwsMiner.WfsmResult(child, W, sup, wsup));
                if (stats != null) stats.childPatternsEmitted++;
                expand(child, db, ec, wdt, tauWsup, stats, output);
            } else if (maxPws >= tauWsup) {
                expand(child, db, ec, wdt, tauWsup, stats, output);
            }
        }
    }

    /**
     * MaxPWS variant that uses WDT instead of a single FTW value. For an
     * extension to {@code m} edges, the heaviest possible additional
     * weight is approximated by the sum of the top-{@code (m - |g|)}
     * weights drawn from the WDT (across all triples). On static data
     * this matches FTW × (m - |g|) by construction.
     */
    private static double dewgMaxPws(Pattern g, double avgW,
                                     OccurrenceList ol, WeightDistributionTable wdt) {
        int gSize = g.code().numEdges();
        double currentSum = gSize * avgW;
        double best = 0.0;
        int[] edgeCounts = ol.edgeCounts();
        int[] mpfs = ol.mpfArrayAscending();
        for (int i = 0; i < edgeCounts.length; i++) {
            int m = edgeCounts[i];
            int mpf = mpfs[i];
            if (mpf <= 0 || m < gSize) continue;
            double extension = wdt.sumOfTopK(m - gSize);
            double pws = ((currentSum + extension) / m) * mpf;
            if (pws > best) best = pws;
        }
        return best;
    }

    private static double patternAverageWeight(Pattern p) {
        for (int gid : p.occursIn()) {
            List<Embedding> embs = p.embeddingsInGraph(gid);
            if (!embs.isEmpty()) return embs.get(0).wAvg();
        }
        return 0.0;
    }

    private static OccurrenceList occurrenceListOf(Pattern p, EdgeClass ec) {
        Map<Integer, Integer> graphToPartition = new HashMap<>();
        for (var part : ec.partitionsAscending()) {
            for (int gid : part.graphIds) graphToPartition.put(gid, part.edgeCount);
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
}
