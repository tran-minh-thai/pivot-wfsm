package pivotwfsm.baselines.wfsm;

/**
 * Possible Weighted Support (PWS) and MaxPWS bound (paper §4.2,
 * Equation (10)).
 *
 * <p>For a subgraph {@code g} with |g| edges, current average weight
 * {@code W(g)}, and First Tuple Weight {@code FTW} (the heaviest
 * canonical-extension weight, per Lemma 1):
 *
 * <pre>
 *   PWS(g_m) = ((|g| * W(g) + (m - |g|) * FTW) / m) * mpf(g_m)
 *
 *   MaxPWS(g) = max_m PWS(g_m)
 * </pre>
 *
 * <p>where {@code m} ranges over the edge counts of the partitions in the
 * occurrence list, and {@code mpf(g_m)} is the count of graphs in
 * partitions of size &geq; {@code m} that still contain {@code g}.
 *
 * <p>If {@link MaxPwsBound} stays below the user threshold {@code tau_w},
 * the subgraph cannot be a frequent weighted subgraph after any extension
 * - safely prune.
 *
 * <p>This class is the only place that owns the formula; tests verify it
 * reproduces paper §4.4 numerics (MaxPWS(gx) = 4.1625).
 */
public final class MaxPwsBound {

    private MaxPwsBound() {}

    /**
     * PWS at a specific target edge count {@code m} and partition reach
     * {@code mpfAtM}.
     */
    public static double pwsAt(int gSize, double avgW, double ftw, int m, int mpfAtM) {
        if (m <= 0 || mpfAtM <= 0) return 0.0;
        if (m < gSize) {
            throw new IllegalArgumentException(
                "m=" + m + " smaller than |g|=" + gSize + " is meaningless");
        }
        double numer = gSize * avgW + (m - gSize) * ftw;
        return (numer / m) * mpfAtM;
    }

    /**
     * MaxPWS over a list of (m, mpf) pairs. Inputs must correspond to the
     * partition-edge-counts and their reachable mpf values (paper §4.2 last
     * paragraph: "from the highest to the lowest OLM").
     *
     * @param gSize       |g|, the current pattern's edge count
     * @param avgW        W(g), the pattern's average edge weight
     * @param ftw         First Tuple Weight (Lemma 1)
     * @param edgeCounts  partition edge counts in ascending order
     * @param mpfFromTop  mpf(g_m) for each edge count, indexed parallel to
     *                    {@code edgeCounts}; should be cumulative from the
     *                    top partition down (Definition 3)
     */
    public static double maxPwsOver(int gSize, double avgW, double ftw,
                                    int[] edgeCounts, int[] mpfFromTop) {
        if (edgeCounts.length != mpfFromTop.length) {
            throw new IllegalArgumentException("edgeCounts/mpf length mismatch");
        }
        double best = 0.0;
        for (int i = 0; i < edgeCounts.length; i++) {
            double v = pwsAt(gSize, avgW, ftw, edgeCounts[i], mpfFromTop[i]);
            if (v > best) best = v;
        }
        return best;
    }
}
