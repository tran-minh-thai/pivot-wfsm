package pivotwfsm.cli;

import pivotwfsm.core.MultiGraphDB;
import pivotwfsm.pivot.PivotWfsmMiner;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Reports the per-phase time decomposition of one \PivotWFSM{} run (principle
 * C11: expose the bottleneck). Phases: index build, F1, candidate generation,
 * canonical check, matching. Warm JVM: {@code warmups} untimed runs, then the
 * median phase profile over {@code timed} runs.
 *
 * <p>Usage: {@code PhaseBreakdown <datasetPath> <sigmaRel> <tauW> [warmups=2] [timed=5]}
 */
public final class PhaseBreakdown {

    private PhaseBreakdown() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: PhaseBreakdown <datasetPath> <sigmaRel> <tauW> [warmups=2] [timed=5]");
            System.exit(2);
        }
        Path path = Path.of(args[0]);
        double sigmaRel = Double.parseDouble(args[1]);
        double tauW = Double.parseDouble(args[2]);
        int warmups = args.length >= 4 ? Integer.parseInt(args[3]) : 2;
        int timed = args.length >= 5 ? Integer.parseInt(args[4]) : 5;

        MultiGraphDB db = MultiGraphDB.loadJson(path);
        int sigmaMin = Math.max(1, (int) Math.ceil(sigmaRel * db.size()));

        for (int i = 0; i < warmups; i++) {
            PivotWfsmMiner.mine(db, sigmaMin, tauW);
        }

        long index = 0, f1 = 0, cand = 0, canon = 0, match = 0, total = 0;
        int patterns = -1;
        for (int i = 0; i < timed; i++) {
            long t0 = System.nanoTime();
            PivotWfsmMiner.Result r = PivotWfsmMiner.mine(db, sigmaMin, tauW);
            long wall = System.nanoTime() - t0;
            PivotWfsmMiner.PhaseTimes p = r.stats().phases();
            index += p.indexNanos();
            f1 += p.f1Nanos();
            cand += p.candidateGenNanos();
            canon += p.canonicalNanos();
            match += p.matchingNanos();
            total += wall;
            patterns = r.patterns().size();
        }
        double ms = 1e6;
        String name = path.getFileName().toString();
        // All time columns are per-run averages (ms).
        // CSV: dataset,sigmaRel,tauW,patterns,total_ms,index_ms,f1_ms,candGen_ms,canonical_ms,matching_ms,matching_pct
        System.out.printf(Locale.ROOT, "%s,%.4f,%.3f,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f%n",
            name, sigmaRel, tauW, patterns,
            total / ms / timed, index / ms / timed, f1 / ms / timed, cand / ms / timed,
            canon / ms / timed, match / ms / timed,
            100.0 * match / (double) total);
    }
}
