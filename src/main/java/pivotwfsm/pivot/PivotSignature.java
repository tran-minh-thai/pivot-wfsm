package pivotwfsm.pivot;

import java.util.HashMap;
import java.util.Map;

/**
 * Structural signature of the pivot inside
 * its pattern, used as a necessary-condition filter over data vertices.
 */
public record PivotSignature(
    int pivotLabel,
    int pivotDegree,
    Map<Integer, Integer> neighborLabelCount,
    Map<Integer, Integer> incidentEdgeLabelCount,
    int[] neighborDegreesDescending
) {

    public static PivotSignature build(PatternGraph pattern, int pivot) {
        Map<Integer, Integer> neighborLabelCount = new HashMap<>();
        Map<Integer, Integer> incidentEdgeLabelCount = new HashMap<>();
        int[] neighborDegrees = new int[pattern.degree(pivot)];

        int i = 0;
        for (PatternGraph.Adj adj : pattern.adjacencyOf(pivot)) {
            neighborLabelCount.merge(pattern.vertexLabel(adj.neighbor()), 1, Integer::sum);
            incidentEdgeLabelCount.merge(adj.edgeLabel(), 1, Integer::sum);
            neighborDegrees[i++] = pattern.degree(adj.neighbor());
        }

        sortDescending(neighborDegrees);
        return new PivotSignature(
            pattern.vertexLabel(pivot),
            pattern.degree(pivot),
            Map.copyOf(neighborLabelCount),
            Map.copyOf(incidentEdgeLabelCount),
            neighborDegrees);
    }

    private static void sortDescending(int[] values) {
        java.util.Arrays.sort(values);
        for (int lo = 0, hi = values.length - 1; lo < hi; lo++, hi--) {
            int tmp = values[lo];
            values[lo] = values[hi];
            values[hi] = tmp;
        }
    }
}
