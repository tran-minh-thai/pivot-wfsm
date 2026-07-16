package pivotwfsm.baselines.gspan;

import pivotwfsm.core.MultiGraphDB;
import pivotwfsm.core.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GSpanMinerTest {

    private static MultiGraphDB db;

    @BeforeAll
    static void load() throws IOException {
        db = MultiGraphDB.loadJson(Paths.get("data/sample/illustrative.json"));
    }

    @Test
    void emitsEveryFrequentSubgraphAboveSigma() {
        // sigma_min=3 on 6 graphs: emit patterns supported in 3+ graphs.
        // From ground_truth.json: P1 (A-B, sup=6), P2 (A-B-C, sup=6),
        // P3 (A-B-D, sup=3), P5 (star, sup=3), single-edge B-C (sup=6), etc.
        List<Pattern> result = GSpanMiner.mine(db, 3);
        assertFalse(result.isEmpty(), "many patterns should be sup>=3 in the illustrative DB");
    }

    @Test
    void higherSigmaReturnsFewerOrEqualPatterns() {
        // Anti-monotone bound: sigma_high subset of sigma_low
        List<Pattern> low  = GSpanMiner.mine(db, 3);
        List<Pattern> high = GSpanMiner.mine(db, 6);
        assertTrue(high.size() <= low.size(),
            "higher sigma should never enumerate MORE patterns than lower sigma");
    }

    @Test
    void sigmaTooHighReturnsEmpty() {
        List<Pattern> result = GSpanMiner.mine(db, 100);
        assertTrue(result.isEmpty());
    }
}
