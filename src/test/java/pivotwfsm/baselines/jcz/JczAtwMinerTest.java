package pivotwfsm.baselines.jcz;

import pivotwfsm.core.MultiGraphDB;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JczAtwMinerTest {

    @Test
    void atwAcceptsAllPatternsAtZeroThreshold() throws IOException {
        // tau=0.0 means every pattern with any occurrence clears ATW (since
        // ATW > 0 for any non-empty occurrence list). Acts as a smoke test
        // that the miner runs to completion.
        MultiGraphDB db = MultiGraphDB.loadJson(Paths.get("data/sample/illustrative.json"));
        List<JczAtwMiner.JczResult> result = JczAtwMiner.mine(db, 0.0);
        assertFalse(result.isEmpty(), "ATW>0 patterns should be discovered");
    }

    @Test
    void atwAtOneRequiresPatternToHitEveryGraph() throws IOException {
        // ATW(g) = sum_{G containing g} Wavg(G) / sum_all_G Wavg(G).
        // ATW = 1 iff numerator equals denominator iff every G in DB contains g.
        // On the illustrative dataset many patterns have full coverage (e.g. A-B).
        MultiGraphDB db = MultiGraphDB.loadJson(Paths.get("data/sample/illustrative.json"));
        List<JczAtwMiner.JczResult> result = JczAtwMiner.mine(db, 1.0);
        for (JczAtwMiner.JczResult r : result) {
            // Every emitted pattern must reach ATW = 1.0 (within rounding).
            assertEquals(1.0, r.atw, 1e-9,
                "tau=1.0 should only accept patterns with ATW == 1");
        }
    }

    @Test
    void atwAboveOneIsImpossible() throws IOException {
        MultiGraphDB db = MultiGraphDB.loadJson(Paths.get("data/sample/illustrative.json"));
        assertTrue(JczAtwMiner.mine(db, 1.01).isEmpty(),
            "ATW is bounded by 1 by construction");
    }
}
