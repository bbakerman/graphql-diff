package com.graphql.diff.reporting;

import com.graphql.diff.DiffEvent;
import graphql.PublicApi;

import java.util.Arrays;
import java.util.List;

/**
 * A reporter that chains together one or more reporters
 */
@PublicApi
public class ChainedReporter implements DifferenceReporter {
    private final List<DifferenceReporter> reporters;

    public ChainedReporter(DifferenceReporter... reporters) {
        this(Arrays.asList(reporters));
    }

    public ChainedReporter(List<DifferenceReporter> reporters) {
        this.reporters = reporters;
    }

    @Override
    public void report(DiffEvent differenceEvent) {
        reporters.forEach(reporter -> reporter.report(differenceEvent));
    }

    @Override
    public void onEnd() {
        reporters.forEach(DifferenceReporter::onEnd);
    }
}
