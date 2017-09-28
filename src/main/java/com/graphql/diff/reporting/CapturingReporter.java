package com.graphql.diff.reporting;

import com.graphql.diff.DiffEvent;
import com.graphql.diff.DiffLevel;
import graphql.PublicApi;

import java.util.ArrayList;
import java.util.List;

/**
 * A reporter that captures all the difference events
 */
@PublicApi
public class CapturingReporter implements DifferenceReporter {
    private final List<DiffEvent> events = new ArrayList<>();
    private final List<DiffEvent> breakages = new ArrayList<>();
    private final List<DiffEvent> dangers = new ArrayList<>();

    @Override
    public void report(DiffEvent differenceEvent) {
        events.add(differenceEvent);
        if (differenceEvent.getLevel() == DiffLevel.BREAKING) {
            breakages.add(differenceEvent);
        }
        if (differenceEvent.getLevel() == DiffLevel.DANGEROUS) {
            dangers.add(differenceEvent);
        }
    }

    @Override
    public void onEnd() {
    }

    public List<DiffEvent> getEvents() {
        return new ArrayList<>(events);
    }

    public List<DiffEvent> getBreakages() {
        return new ArrayList<>(breakages);
    }

    public List<DiffEvent> getDangers() {
        return new ArrayList<>(dangers);
    }

    public int getBreakageCount() {
        return breakages.size();
    }

    public int getDangerCount() {
        return dangers.size();
    }

}
