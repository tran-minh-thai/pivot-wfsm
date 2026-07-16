package pivotwfsm.pivot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Filters the vertices of one host
 * graph down to those whose local statistics cover the pattern's
 * {@link PivotSignature}. Every check is a necessary condition, so no true
 * pivot host is ever dropped (proved in the paper).
 */
public final class PivotCandidateFilter {

    private PivotCandidateFilter() {
    }

    public static List<Integer> findCandidatePivotNodes(PivotSignature signature,
                                                        HostGraphIndex hostGraph) {
        List<Integer> candidates = new ArrayList<>();
        for (int v = 0; v < hostGraph.vertexCount(); v++) {
            if (covers(signature, hostGraph, v)) {
                candidates.add(v);
            }
        }
        return candidates;
    }

    private static boolean covers(PivotSignature signature, HostGraphIndex hostGraph, int v) {
        HostGraphIndex.Signature host = hostGraph.signatureOf(v);

        if (host.vertexLabel() != signature.pivotLabel()) {
            return false;
        }
        if (host.degree() < signature.pivotDegree()) {
            return false;
        }

        Map<Integer, Integer> hostLabels = host.neighborLabelHistogram();
        for (Map.Entry<Integer, Integer> needed : signature.neighborLabelCount().entrySet()) {
            if (hostLabels.getOrDefault(needed.getKey(), 0) < needed.getValue()) {
                return false;
            }
        }

        Map<Integer, Integer> hostEdgeLabels = new HashMap<>();
        for (HostGraphIndex.NeighborRef ref : hostGraph.neighborsOf(v)) {
            hostEdgeLabels.merge(ref.edgeLabel(), 1, Integer::sum);
        }
        for (Map.Entry<Integer, Integer> needed : signature.incidentEdgeLabelCount().entrySet()) {
            if (hostEdgeLabels.getOrDefault(needed.getKey(), 0) < needed.getValue()) {
                return false;
            }
        }

        int[] neededDegrees = signature.neighborDegreesDescending();
        if (neededDegrees.length > 0) {
            HostGraphIndex.NeighborRef[] neighbors = hostGraph.neighborsOf(v);
            int[] hostDegrees = new int[neighbors.length];
            for (int i = 0; i < neighbors.length; i++) {
                hostDegrees[i] = hostGraph.degreeOf(neighbors[i].toVertex());
            }
            java.util.Arrays.sort(hostDegrees);
            reverse(hostDegrees);
            for (int i = 0; i < neededDegrees.length; i++) {
                if (hostDegrees[i] < neededDegrees[i]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void reverse(int[] values) {
        for (int lo = 0, hi = values.length - 1; lo < hi; lo++, hi--) {
            int tmp = values[lo];
            values[lo] = values[hi];
            values[hi] = tmp;
        }
    }
}
