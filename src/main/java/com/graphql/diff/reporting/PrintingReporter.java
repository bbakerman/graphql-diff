package com.graphql.diff.reporting;

public class PrintingReporter implements DifferenceReporter {

    int errorCount = 0;
    int warningCount = 0;

    @Override
    public void report(DifferenceEvent differenceEvent) {
        if (differenceEvent.getLevel() == DifferenceEvent.Level.ERROR) {
            errorCount++;
        }
        if (differenceEvent.getLevel() == DifferenceEvent.Level.WARNING) {
            warningCount++;
        }

        printEvent(differenceEvent);
    }

    @Override
    public void onEnd() {
        System.out.println("\n");
        System.out.println(String.format("%d errors", errorCount));
        System.out.println(String.format("%d warnings", warningCount));
    }

    private void printEvent(DifferenceEvent event) {
        System.out.println(String.format(
                "%s - %s type named '%s' : %s", event.getLevel(), event.getTypeOfType(), event.getTypeName(), event.getReasonMsg()));
    }

    public int getErrorCount() {
        return errorCount;
    }

    public int getWarningCount() {
        return warningCount;
    }
}
