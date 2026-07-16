package pivotwfsm.baselines.wfsm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Occurrence List of a subgraph in a partitioned database (paper §4.2,
 * Definition 3).
 *
 * <p>OL(g) is a list of Occurrence List Members (OLM), one per partition
 * {@code p_m} of the Edge Class. Each OLM holds the subset of graph ids in
 * {@code p_m} that contain {@code g} as a subgraph.
 *
 * <p>Two derived measures, used by {@link MaxPwsBound}:
 * <ul>
 *   <li>{@code possibleOccurrenceList(m)} (pol) - the set of graphs that
 *       could still support an extension of {@code g} up to {@code m}
 *       edges; defined as the union of OLMs in partitions whose edge count
 *       is &geq; {@code m}.</li>
 *   <li>{@code maxPossibleFrequency(m)} (mpf) - the cardinality of pol.</li>
 * </ul>
 */
public final class OccurrenceList {

    /** One Occurrence List Member: a (partition edge count, graph ids) pair. */
    public static final class OLM {
        public final int edgeCount;
        public final int[] graphIds;

        public OLM(int edgeCount, int[] graphIds) {
            this.edgeCount = edgeCount;
            this.graphIds = graphIds;
        }

        public int size() { return graphIds.length; }
    }

    private final List<OLM> membersAscending;

    public OccurrenceList(List<OLM> membersAscending) {
        // Defensive copy, then validate ascending order on edgeCount.
        List<OLM> sorted = new ArrayList<>(membersAscending);
        sorted.sort((a, b) -> Integer.compare(a.edgeCount, b.edgeCount));
        this.membersAscending = Collections.unmodifiableList(sorted);
    }

    public List<OLM> members() { return membersAscending; }

    /** Total number of occurring graphs across all partitions. */
    public int totalOccurrences() {
        int n = 0;
        for (OLM m : membersAscending) n += m.size();
        return n;
    }

    /** Cardinality of the OL - the transactional support. */
    public int support() { return totalOccurrences(); }

    /**
     * mpf(g_m) - cardinality of pol(g_m), i.e. number of host graphs in
     * partitions of edge count &geq; {@code m} that still contain {@code g}.
     */
    public int maxPossibleFrequency(int m) {
        int n = 0;
        for (OLM o : membersAscending) {
            if (o.edgeCount >= m) n += o.size();
        }
        return n;
    }

    /**
     * Convenience: return parallel arrays of (edge count, mpf) suitable for
     * {@link MaxPwsBound#maxPwsOver(int, double, double, int[], int[])}.
     */
    public int[] edgeCounts() {
        int[] arr = new int[membersAscending.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = membersAscending.get(i).edgeCount;
        return arr;
    }

    public int[] mpfArrayAscending() {
        int[] arr = new int[membersAscending.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = maxPossibleFrequency(membersAscending.get(i).edgeCount);
        }
        return arr;
    }
}
