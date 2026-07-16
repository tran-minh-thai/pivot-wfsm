package pivotwfsm.cli;

import pivotwfsm.baselines.gspan.GSpanMiner;
import pivotwfsm.baselines.jcz.JczAtwMiner;
import pivotwfsm.baselines.wfsm.DewgSpanMiner;
import pivotwfsm.baselines.wfsm.WfsmMaxPwsMiner;
import pivotwfsm.core.MultiGraphDB;
import pivotwfsm.miner.EmbeddingStoreMiner;
import pivotwfsm.pivot.PivotWfsmMiner;
import pivotwfsm.weight.MinAggregator;

import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs ONE (algorithm, dataset, sigma, tau) config in a fresh JVM and prints one
 * CSV row with every metric the paper needs.
 *
 * <p><b>Memory metric.</b> The headline figure is the <i>peak live heap</i>: the
 * largest heap occupancy observed immediately after a garbage collection, read
 * from GC notifications (no forced GC, so timing is not perturbed). This is the
 * quantity that peak-RSS measures for a non-GC (C/C++) implementation, and it is
 * the one the complexity analysis talks about.
 *
 * <p>The raw "peak used heap" is reported too (last column) but is NOT the
 * headline: under a managed runtime it counts garbage that has not been
 * collected yet, so it grows with {@code -Xmx} and with GC laziness rather than
 * with the algorithm's requirement. It therefore penalises exactly the strategy
 * that allocates short-lived objects (on-demand re-matching) and flatters the one
 * that retains live ones (embedding storage).
 *
 * <p>Usage: {@code SingleRun <algo> <datasetPath> <sigmaRel> <tauW> [warmups=0] [timed=1]}
 * <p>Algorithms: pivot, pivot-plain, pivot-nopf, pivot-plain-nopf, embed-min,
 * gspan, jcz-atw, wfsm-maxpws, dewgspan.
 *
 * <p>Output columns:
 * {@code algo,dataset,graphs,sigmaRel,sigmaMin,tauW,meanMs,bestMs,patterns,
 * peakLiveHeapMb,candidates,prefiltered,nonCanonical,evaluated,peakUsedHeapMb}.
 * Columns 11-14 (search-space statistics) are reported for the pivot variants;
 * baselines emit {@code -1}.
 */
public final class SingleRun {

    private SingleRun() {
    }

    /** One run's outcome: pattern count plus (for pivot) search-space stats. */
    private record Outcome(int patterns, long candidates, long prefiltered,
                           long nonCanonical, long evaluated) {
        static Outcome ofSize(int n) {
            return new Outcome(n, -1, -1, -1, -1);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("""
                usage: SingleRun <algo> <datasetPath> <sigmaRel> <tauW> [warmups=0] [timed=1]

                  algo        pivot             on-demand re-matching (this paper)
                              pivot-plain       ablation: no pivot anchoring
                              pivot-nopf        ablation: no label-pair prefilter
                              pivot-plain-nopf  ablation: neither
                              embed-min         embedding-store baseline, same MIN measure
                              gspan | jcz-atw | wfsm-maxpws | dewgspan   published baselines
                  sigmaRel    minimum support as a fraction of the database size, e.g. 0.10
                  tauW        edge-weight threshold in (0, 1], e.g. 0.5
                  warmups     untimed runs before measuring (JVM warm-up)
                  timed       measured runs; the row reports their mean and best

                Prints one CSV row (no header):
                  algo,dataset,graphs,sigmaRel,sigmaMin,tauW,meanMs,bestMs,patterns,
                  peakLiveHeapMb,candidates,prefiltered,nonCanonical,evaluated,peakUsedHeapMb
                The last four search-space counters are -1 for the baselines.
                Memory to quote is peakLiveHeapMb; see the class javadoc for why.
                """);
            System.exit(2);
        }
        String algo = args[0].toLowerCase(Locale.ROOT);
        Path path = Path.of(args[1]);
        double sigmaRel = Double.parseDouble(args[2]);
        double tauW = Double.parseDouble(args[3]);
        int warmups = args.length >= 5 ? Integer.parseInt(args[4]) : 0;
        int timed = args.length >= 6 ? Integer.parseInt(args[5]) : 1;

        MultiGraphDB db = MultiGraphDB.loadJson(path);
        int sigmaMin = Math.max(1, (int) Math.ceil(sigmaRel * db.size()));

        for (int i = 0; i < warmups; i++) {
            runOnce(algo, db, sigmaMin, tauW);
        }

        System.gc();

        // --- Peak LIVE heap (headline): max heap occupancy right after a GC. ---
        // Read from GC notifications, so no GC is forced and timing is untouched.
        // This is what peak-RSS measures for a non-GC implementation.
        final Set<String> heapPools = new HashSet<>();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) {
                heapPools.add(p.getName());
            }
        }
        final AtomicLong peakLiveBytes = new AtomicLong(0);
        final List<NotificationEmitter> emitters = new ArrayList<>();
        final List<NotificationListener> listeners = new ArrayList<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(gc instanceof NotificationEmitter emitter)) {
                continue;
            }
            NotificationListener listener = (notification, handback) -> {
                if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                        .equals(notification.getType())) {
                    return;
                }
                GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo
                    .from((CompositeData) notification.getUserData());
                long live = 0;
                for (Map.Entry<String, MemoryUsage> e
                        : info.getGcInfo().getMemoryUsageAfterGc().entrySet()) {
                    if (heapPools.contains(e.getKey())) {
                        live += e.getValue().getUsed();
                    }
                }
                final long observed = live;
                peakLiveBytes.updateAndGet(prev -> Math.max(prev, observed));
            };
            emitter.addNotificationListener(listener, null, null);
            emitters.add(emitter);
            listeners.add(listener);
        }

        // --- Raw peak USED heap (reference only, see class javadoc). ---
        final java.lang.management.MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        final AtomicLong peakUsedBytes =
            new AtomicLong(memBean.getHeapMemoryUsage().getUsed());
        final java.util.concurrent.atomic.AtomicBoolean sampling =
            new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                long used = memBean.getHeapMemoryUsage().getUsed();
                peakUsedBytes.updateAndGet(prev -> Math.max(prev, used));
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "heap-sampler");
        sampler.setDaemon(true);
        sampler.start();

        long best = Long.MAX_VALUE;
        long sum = 0;
        Outcome last = Outcome.ofSize(-1);
        for (int i = 0; i < timed; i++) {
            long t0 = System.nanoTime();
            last = runOnce(algo, db, sigmaMin, tauW);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            best = Math.min(best, ms);
            sum += ms;
        }
        long meanMs = sum / Math.max(1, timed);

        sampling.set(false);
        try {
            sampler.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (int i = 0; i < emitters.size(); i++) {
            try {
                emitters.get(i).removeNotificationListener(listeners.get(i));
            } catch (javax.management.ListenerNotFoundException ignored) {
                // listener already gone; nothing to undo
            }
        }

        // If the workload never triggered a GC, its live set never pressured the
        // heap; fall back to the occupancy after one explicit collection.
        if (peakLiveBytes.get() == 0) {
            System.gc();
            peakLiveBytes.set(memBean.getHeapMemoryUsage().getUsed());
        }
        long peakHeapMb = peakLiveBytes.get() / (1024 * 1024);
        long peakUsedMb = peakUsedBytes.get() / (1024 * 1024);

        System.out.printf(Locale.ROOT, "%s,%s,%d,%.4f,%d,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
            algo, path.getFileName(), db.size(), sigmaRel, sigmaMin, tauW,
            meanMs, best, last.patterns(), peakHeapMb,
            last.candidates(), last.prefiltered(), last.nonCanonical(), last.evaluated(),
            peakUsedMb);
    }

    private static Outcome runOnce(String algo, MultiGraphDB db, int sigmaMin, double tauW) {
        return switch (algo) {
            case "pivot" -> pivot(db, sigmaMin, tauW,
                PivotWfsmMiner.PrefilterMode.ON, PivotWfsmMiner.Matching.PIVOT);
            case "pivot-plain" -> pivot(db, sigmaMin, tauW,
                PivotWfsmMiner.PrefilterMode.ON, PivotWfsmMiner.Matching.PLAIN);
            case "pivot-nopf" -> pivot(db, sigmaMin, tauW,
                PivotWfsmMiner.PrefilterMode.OFF, PivotWfsmMiner.Matching.PIVOT);
            case "pivot-plain-nopf" -> pivot(db, sigmaMin, tauW,
                PivotWfsmMiner.PrefilterMode.OFF, PivotWfsmMiner.Matching.PLAIN);
            case "embed-min" -> Outcome.ofSize(EmbeddingStoreMiner.mineWithPrefilter(
                db, sigmaMin, tauW, new MinAggregator()).size());
            case "gspan" -> Outcome.ofSize(GSpanMiner.mine(db, sigmaMin, null).size());
            case "jcz-atw" -> Outcome.ofSize(JczAtwMiner.mine(db, tauW, sigmaMin).size());
            case "wfsm-maxpws" -> Outcome.ofSize(WfsmMaxPwsMiner.mine(db, tauW).size());
            case "dewgspan" -> Outcome.ofSize(DewgSpanMiner.mine(db, tauW).size());
            default -> throw new IllegalArgumentException("unknown algo: " + algo);
        };
    }

    private static Outcome pivot(MultiGraphDB db, int sigmaMin, double tauW,
                                 PivotWfsmMiner.PrefilterMode pf, PivotWfsmMiner.Matching m) {
        PivotWfsmMiner.Result r = PivotWfsmMiner.mine(db, sigmaMin, tauW, pf, m);
        PivotWfsmMiner.Stats s = r.stats();
        return new Outcome(r.patterns().size(), s.candidatesGenerated(),
            s.prefilterSkipped(), s.nonCanonicalSkipped(), s.evaluated());
    }
}
