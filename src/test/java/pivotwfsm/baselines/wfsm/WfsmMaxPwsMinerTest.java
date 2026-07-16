package pivotwfsm.baselines.wfsm;

import pivotwfsm.core.MultiGraph;
import pivotwfsm.core.MultiGraphDB;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke test for {@link WfsmMaxPwsMiner} on a synthetic
 * static-edge-weight database that mirrors the structural shape of the
 * paper's Figure 3 simulation (three partitions by edge count, predictable
 * MaxPWS arithmetic).
 *
 * <p>Also verifies the static-weight validation rejects multigraph-style
 * inputs like {@code data/sample/illustrative.json} cleanly.
 */
class WfsmMaxPwsMinerTest {

    @Test
    void rejectsIllustrativeBecauseOfParallelEdgesWithDifferentWeights() throws IOException {
        // illustrative.json has two parallel A-B edges (0.85, 0.40) in G0 →
        // violates the static-weight assumption. The miner must surface this
        // loudly, not silently mine garbage.
        MultiGraphDB db = MultiGraphDB.loadJson(Paths.get("data/sample/illustrative.json"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> WfsmMaxPwsMiner.validateStaticWeights(db));
        assertTrue(ex.getMessage().contains("static edge weights"),
            "error message must explain the assumption violation");
    }

    @Test
    void minesEmptyOnEmptyDb() {
        MultiGraphDB empty = newDb("empty");
        List<WfsmMaxPwsMiner.WfsmResult> result = WfsmMaxPwsMiner.mine(empty, 0.5);
        assertTrue(result.isEmpty());
    }

    @Test
    void singleGraphSingleEdgeIsFrequentIfWsupClearsThreshold() {
        // One graph with one A-B edge of weight 0.8. Pattern (A,B) has
        // W(g)=0.8, support=1 → wsup=0.8. Frequent iff tau <= 0.8.
        MultiGraph g0 = MultiGraph.of(0,
            new int[]{0, 1},
            new int[]{0},
            new int[]{1},
            new double[]{0.8});
        MultiGraphDB db = newDb("one_edge", g0);

        List<WfsmMaxPwsMiner.WfsmResult> frequent = WfsmMaxPwsMiner.mine(db, 0.5);
        assertEquals(1, frequent.size());
        WfsmMaxPwsMiner.WfsmResult r = frequent.get(0);
        assertEquals(0.8, r.averageWeight, 1e-12);
        assertEquals(1, r.support);
        assertEquals(0.8, r.weightedSupport, 1e-12);

        // Same dataset, higher threshold → empty.
        assertTrue(WfsmMaxPwsMiner.mine(db, 0.9).isEmpty());
    }

    @Test
    void multipleStaticWeightGraphs_pruneByMaxPws() {
        // Three graphs, all with A-B edges of weight 0.6. Pattern (A,B) has
        // wsup = 0.6 * 3 = 1.8. With tau=1.0 it's frequent; with tau=2.0 it
        // gets pruned by MaxPWS (since with no heavier edges, FTW=0.6 keeps
        // any extension below 2.0).
        MultiGraph g0 = MultiGraph.of(0, new int[]{0, 1}, new int[]{0}, new int[]{1}, new double[]{0.6});
        MultiGraph g1 = MultiGraph.of(1, new int[]{0, 1}, new int[]{0}, new int[]{1}, new double[]{0.6});
        MultiGraph g2 = MultiGraph.of(2, new int[]{0, 1}, new int[]{0}, new int[]{1}, new double[]{0.6});
        MultiGraphDB db = newDb("three_same", g0, g1, g2);

        assertEquals(1, WfsmMaxPwsMiner.mine(db, 1.0).size(),
            "wsup = 0.6 * 3 = 1.8 should clear tau=1.0");
        assertTrue(WfsmMaxPwsMiner.mine(db, 2.0).isEmpty(),
            "wsup = 1.8 < 2.0 should be pruned by MaxPWS (no heavier extension possible)");
    }

    @Test
    void edgeClassPartitionsByEdgeCount() {
        // 2 graphs with 1 edge, 1 graph with 2 edges → two partitions.
        MultiGraph g0 = MultiGraph.of(0, new int[]{0, 1}, new int[]{0}, new int[]{1}, new double[]{0.7});
        MultiGraph g1 = MultiGraph.of(1, new int[]{0, 1}, new int[]{0}, new int[]{1}, new double[]{0.7});
        MultiGraph g2 = MultiGraph.of(2, new int[]{0, 1, 2},
            new int[]{0, 1},
            new int[]{1, 2},
            new double[]{0.7, 0.7});
        MultiGraphDB db = newDb("mixed_sizes", g0, g1, g2);

        EdgeClass ec = EdgeClass.build(db);
        assertEquals(2, ec.size());
        assertEquals(1, ec.minEdges());
        assertEquals(2, ec.maxEdges());
    }

    // ---------------------------------------------------------------- helper

    /** Build a small {@link MultiGraphDB} for tests by reflection (no public ctor). */
    private static MultiGraphDB newDb(String name, MultiGraph... graphs) {
        try {
            Constructor<MultiGraphDB> ctor = MultiGraphDB.class.getDeclaredConstructor(
                String.class, java.util.List.class, java.util.List.class);
            ctor.setAccessible(true);
            return ctor.newInstance(name,
                Collections.<String>emptyList(),
                java.util.Arrays.asList(graphs));
        } catch (NoSuchMethodException | IllegalAccessException
                 | InstantiationException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
