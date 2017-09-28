package com.graphql.diff;

import graphql.PublicApi;

/**
 * A classification of difference events.
 */
@PublicApi
public enum DiffCategory {
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
