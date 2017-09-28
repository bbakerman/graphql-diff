package com.graphql.diff.reporting;

import com.graphql.diff.DiffEvent;
import graphql.PublicSpi;

/**
 * This is called with each different encountered (including info ones)
 */
@PublicSpi
public interface DifferenceReporter {

    /**
     * Called to report a difference
     *
     * @param differenceEvent the event describing the difference
     */
    void report(DiffEvent differenceEvent);

    /**
     * Called when the difference operation if finished
     */
    void onEnd();
}
