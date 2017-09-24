package com.graphql.diff.reporting;

/**
 * A classification of difference events.
 */
public enum DifferenceCategory {
    /**
     * The new API is missing something
     */
    MISSING,
    /**
     * The new API has become stricter for existing clients
     */
    STRICTER,
    /**
     * The new API has an invalid structure
     */
    INVALID,
    /**
     * The new API has added something
     */
    ADDITION,
    /**
     * The new API has changed something
     */
    DIFFERENT
}
