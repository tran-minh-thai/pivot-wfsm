package pivotwfsm.baselines.wfsm;

import pivotwfsm.core.MultiGraph;
import pivotwfsm.core.MultiGraphDB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Edge Class partitioning of the database (paper §4.2 Definition 2,
 * Algorithm 1).
 *
 * <p>For each distinct edge count {@code m} present in the database, the
 * Edge Class contains a partition {@code p_m} that lists every graph with
 * exactly {@code m} edges. Partitions are kept sorted by {@code m} so the
 * MaxPWS calculation in {@link MaxPwsBound} can walk them from highest
 * edge count down to lowest (paper §4.2 sentence "from the highest to the
 * lowest OLM(qi)").
 */
public final class EdgeClass {

    /** One partition: edge count + sorted list of graph ids. */
    public static final class Partition {
        public final int edgeCount;
        public final int[] graphIds;

        public Partition(int edgeCount, int[] graphIds) {
            this.edgeCount = edgeCount;
            this.graphIds = graphIds;
        }

        public int size() { return graphIds.length; }

        @Override
        public String toString() {
            return "p_" + edgeCount + "=" + java.util.Arrays.toString(graphIds);
        }
    }

    private final Partition[] partitionsByAscendingEdgeCount;
    private final int minEdges;
    private final int maxEdges;

    private EdgeClass(Partition[] partitions, int minEdges, int maxEdges) {
        this.partitionsByAscendingEdgeCount = partitions;
        this.minEdges = minEdges;
        this.maxEdges = maxEdges;
    }

    /** Algorithm 1 of the paper. Single linear pass over the database. */
    public static EdgeClass build(MultiGraphDB db) {
        NavigableMap<Integer, List<Integer>> buckets = new TreeMap<>();
        int minE = Integer.MAX_VALUE;
        int maxE = Integer.MIN_VALUE;
        for (int gid = 0; gid < db.size(); gid++) {
            MultiGraph g = db.get(gid);
            int m = g.numEdges();
            if (m < minE) minE = m;
            if (m > maxE) maxE = m;
            buckets.computeIfAbsent(m, k -> new ArrayList<>()).add(gid);
        }

        Partition[] parts = new Partition[buckets.size()];
        int i = 0;
        for (var en : buckets.entrySet()) {
            List<Integer> ids = en.getValue();
            int[] arr = ids.stream().mapToInt(Integer::intValue).toArray();
            parts[i++] = new Partition(en.getKey(), arr);
        }
        if (db.size() == 0) { minE = 0; maxE = 0; }
        return new EdgeClass(parts, minE, maxE);
    }

    /** Number of partitions. */
    public int size() { return partitionsByAscendingEdgeCount.length; }

    /** Partitions sorted by edge count ascending. */
    public List<Partition> partitionsAscending() {
        return Collections.unmodifiableList(java.util.Arrays.asList(partitionsByAscendingEdgeCount));
    }

    /** Partitions sorted by edge count descending - the order MaxPWS walks. */
    public List<Partition> partitionsDescending() {
        List<Partition> copy = new ArrayList<>(java.util.Arrays.asList(partitionsByAscendingEdgeCount));
        Collections.reverse(copy);
        return copy;
    }

    public int minEdges() { return minEdges; }
    public int maxEdges() { return maxEdges; }
}
