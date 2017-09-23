package com.graphql.diff;

import com.graphql.diff.reporting.DifferenceEvent;
import com.graphql.diff.reporting.PrintingReporter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class CapturingReporter extends PrintingReporter {
    private final List<DifferenceEvent> events = new ArrayList<>();

    @Override
    public void report(DifferenceEvent differenceEvent) {
        events.add(differenceEvent);
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
        return events.stream().filter(e -> e.getLevel().equals(DifferenceEvent.Level.ERROR)).collect(Collectors.toList());
    }
}
