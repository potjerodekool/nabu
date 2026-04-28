package io.github.potjerodekool.nabu.testing;

import java.io.StringWriter;

public class IndentPrinter {

    private final StringWriter writer = new StringWriter();
    private boolean atStartOfLine = true;
    private final StringBuilder tabs = new StringBuilder();
    private static final String TAB = "    ";
    private static final int TAB_SIZE = TAB.length();

    public IndentPrinter write(final String text) {
        if (!"\n".equals(text)) {
            insertTabsIfNeeded();
        }
        writer.write(text);
        writer.flush();
        detectNewLine(text);
        return this;
    }

    private void detectNewLine(final String text) {
        if (text.contains("\n")) {
            atStartOfLine = true;
        }
    }

    private void insertTabsIfNeeded() {
        if (atStartOfLine) {
            if (!tabs.isEmpty()) {
                writer.write(tabs.toString());
            }
            atStartOfLine = false;
        }
    }

    public void incrementTabs() {
        this.tabs.append(TAB);
    }

    public void decrementTabs() {
        final int length = tabs.length();
        this.tabs.delete(length - TAB_SIZE, length);
    }

    public IndentPrinter writeLine(final String text) {
        insertTabsIfNeeded();
        write(text);
        newLine();
        return this;
    }

    public IndentPrinter newLine() {
        writer.write("\n");
        writer.flush();
        this.atStartOfLine = true;
        return this;
    }

    public String getText() {
        return writer.toString();
    }
}
