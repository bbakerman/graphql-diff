package com.graphql.diff.reporting;

public interface DifferenceReporter {

    void report(DifferenceEvent differenceEvent);

    void onEnd();
}
