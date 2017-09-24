package com.graphql.diff.reporting;

/**
 * This is called with each different encountered (including info ones)
 */
public interface DifferenceReporter {

    /**
     * Called to report a difference
     *
     * @param differenceEvent the event describing the difference
     */
    void report(DifferenceEvent differenceEvent);

    /**
     * Called when the difference operation if finished
     */
    void onEnd();
}
