package pivotwfsm.miner;

/**
 * Lightweight counters populated during a single {@code EmbeddingStoreMiner.mine(...)}
 * call. Used by the prefilter ablation to quantify how much work the unified
 * prefilter saves vs the no-prefilter baseline.
 *
 * <p>All counters reset to zero on construction; the miner increments them
 * in place. Not thread-safe - one instance per mining run.
 */
public final class MiningStats {

    /** Distinct (anchorLabel, targetLabel) tuples the prefilter declared dead. */
    public long extensionTuplesPrefilterSkipped;

    /** Embedding-extension candidates the inner loop considered (regardless of skip). */
    public long embeddingExtensionsConsidered;

    /** Embedding-extension candidates that survived prefilter and were materialised. */
    public long embeddingExtensionsMaterialised;

    /** Child patterns enumerated (after dedup; before frequency check). */
    public long childPatternsEnumerated;

    /** Child patterns rejected by the MinDFSCode canonical check. */
    public long childPatternsRejectedNonCanonical;

    /** Child patterns that passed the emit rule (i.e. became frequent output). */
    public long childPatternsEmitted;

    /** Reset all counters to zero. */
    public void reset() {
        extensionTuplesPrefilterSkipped = 0;
        embeddingExtensionsConsidered = 0;
        embeddingExtensionsMaterialised = 0;
        childPatternsEnumerated = 0;
        childPatternsRejectedNonCanonical = 0;
        childPatternsEmitted = 0;
    }

    @Override
    public String toString() {
        return "MiningStats{"
            + "prefilterSkipped="    + extensionTuplesPrefilterSkipped
            + ", considered="        + embeddingExtensionsConsidered
            + ", materialised="      + embeddingExtensionsMaterialised
            + ", childsEnum="        + childPatternsEnumerated
            + ", childsNonCanonical="+ childPatternsRejectedNonCanonical
            + ", emitted="           + childPatternsEmitted
            + "}";
    }
}
