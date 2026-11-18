package eu.kalafatic.dcode.core.model;

/**
 * Per-repository modification policy of a workspace repository association.
 *
 * <p>Read-only repositories must be enforced by the repository engine, never merely
 * documented. Destructive or mutating operations against a READ_ONLY repository are
 * rejected before execution.</p>
 */
public enum RepositoryAccessPolicy {
    READ_ONLY,
    READ_WRITE
}
