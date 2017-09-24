package com.graphql.diff.reporting;

import java.util.Arrays;
import java.util.List;

/**
 * A reporter that chains together one or more reporters
 */
public class ChainedReporter implements DifferenceReporter {
    private final List<DifferenceReporter> reporters;

    public ChainedReporter(DifferenceReporter... reporters) {
        this(Arrays.asList(reporters));
    }

    public ChainedReporter(List<DifferenceReporter> reporters) {
        this.reporters = reporters;
    }

    @Override
    public void report(DifferenceEvent differenceEvent) {
        reporters.forEach(reporter -> reporter.report(differenceEvent));
    }

    @Override
    public void onEnd() {
        reporters.forEach(DifferenceReporter::onEnd);
    }
}
