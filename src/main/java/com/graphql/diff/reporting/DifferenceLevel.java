package com.graphql.diff.reporting;

/**
 * This is the level of difference
 */
public enum DifferenceLevel {
    /**
     * A simple info object coming out of the difference engine
     */
    INFO,
    /**
     * The new API has made a breaking change
     */
    BREAKING,
    /**
     * The new API has made a dangerous (but non breaking) change
     */
    DANGEROUS
}
