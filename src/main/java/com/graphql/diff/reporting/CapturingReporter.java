package com.graphql.diff.reporting;

import java.util.ArrayList;
import java.util.List;

/**
 * A reporter that captures all the difference events
 */
public class CapturingReporter implements DifferenceReporter {
    private final List<DifferenceEvent> events = new ArrayList<>();
    private final List<DifferenceEvent> breakages = new ArrayList<>();
    private final List<DifferenceEvent> dangers = new ArrayList<>();

    @Override
    public void report(DifferenceEvent differenceEvent) {
        events.add(differenceEvent);
        if (differenceEvent.getLevel() == DifferenceLevel.BREAKING) {
            breakages.add(differenceEvent);
        }
        if (differenceEvent.getLevel() == DifferenceLevel.DANGEROUS) {
            dangers.add(differenceEvent);
        }
    }

    @Override
    public void onEnd() {
    }

    public List<DifferenceEvent> getEvents() {
        return new ArrayList<>(events);
    }

    public List<DifferenceEvent> getBreakages() {
        return new ArrayList<>(breakages);
    }

    public List<DifferenceEvent> getDangers() {
        return new ArrayList<>(dangers);
    }

    public int getBreakageCount() {
        return breakages.size();
    }

    public int getDangerCount() {
        return dangers.size();
    }

}
