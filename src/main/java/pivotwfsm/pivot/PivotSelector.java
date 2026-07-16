package pivotwfsm.pivot;

/**
 * Picks the pattern vertex used as matching
 * anchor from pattern-intrinsic structure only: larger degree, then more
 * distinct neighbour labels, then smaller eccentricity, then smaller id.
 */
public final class PivotSelector {

    private PivotSelector() {
    }

    public static int selectPivot(PatternGraph pattern) {
        int n = pattern.numVertices();
        int best = -1;
        int bestDegree = -1;
        int bestDistinct = -1;
        int bestEccentricity = Integer.MAX_VALUE;

        for (int v = 0; v < n; v++) {
            int degree = pattern.degree(v);
            int distinct = pattern.distinctNeighborLabelCount(v);
            int eccentricity = eccentricity(pattern, v);

            boolean better;
            if (degree != bestDegree) {
                better = degree > bestDegree;
            } else if (distinct != bestDistinct) {
                better = distinct > bestDistinct;
            } else if (eccentricity != bestEccentricity) {
                better = eccentricity < bestEccentricity;
            } else {
                better = false;
            }

            if (best < 0 || better) {
                best = v;
                bestDegree = degree;
                bestDistinct = distinct;
                bestEccentricity = eccentricity;
            }
        }
        return best;
    }

    private static int eccentricity(PatternGraph pattern, int v) {
        int[] dist = pattern.bfsDistances(v);
        int max = 0;
        for (int d : dist) {
            if (d > max) {
                max = d;
            }
        }
        return max;
    }
}
