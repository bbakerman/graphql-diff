package com.graphql.diff;

import com.graphql.diff.reporting.DifferenceEvent;
import com.graphql.diff.reporting.PrintingReporter;

import java.util.ArrayList;
import java.util.List;

public class CapturingReporter extends PrintingReporter {
    private final List<DifferenceEvent> events = new ArrayList<>();
    private final List<DifferenceEvent> errors = new ArrayList<>();

    @Override
    public void report(DifferenceEvent differenceEvent) {
        events.add(differenceEvent);
        if (differenceEvent.getLevel() == DifferenceEvent.Level.ERROR) {
            errors.add(differenceEvent);
        }
        super.report(differenceEvent);
    }

    public List<DifferenceEvent> getEvents() {
        return events;
    }

    public DifferenceEvent lastError() {
        List<DifferenceEvent> errors = getErrors();
        return errors.size() == 0 ? null : errors.get(errors.size() - 1);
    }

    public List<DifferenceEvent> getErrors() {
        return errors;
    }
}
