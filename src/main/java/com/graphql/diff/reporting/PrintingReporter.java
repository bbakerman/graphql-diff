package com.graphql.diff.reporting;

import java.io.PrintStream;

/**
 * A reporter that prints its output to a PrintStream
 */
public class PrintingReporter implements DifferenceReporter {

    int breakageCount = 0;
    int dangerCount = 0;
    final PrintStream out;

    public PrintingReporter() {
        this(System.out);
    }

    public PrintingReporter(PrintStream out) {
        this.out = out;
    }

    @Override
    public void report(DifferenceEvent differenceEvent) {
        if (differenceEvent.getLevel() == DifferenceLevel.BREAKING) {
            breakageCount++;
        }
        if (differenceEvent.getLevel() == DifferenceLevel.DANGEROUS) {
            dangerCount++;
        }

        printEvent(differenceEvent);
    }

    @Override
    public void onEnd() {
        out.println("\n");
        out.println(String.format("%d errors", breakageCount));
        out.println(String.format("%d warnings", dangerCount));
        out.println("\n");
    }

    private void printEvent(DifferenceEvent event) {
        String indent = event.getLevel() == DifferenceLevel.INFO ? "\t" : "";
        String objectName = event.getTypeName();
        if (event.getFieldName() != null) {
            objectName = objectName + "." + event.getFieldName();
        }
        out.println(String.format(
                "%s%s - '%s' : '%s' : %s", indent, event.getLevel(), event.getTypeKind(), objectName, event.getReasonMsg()));
    }
}
