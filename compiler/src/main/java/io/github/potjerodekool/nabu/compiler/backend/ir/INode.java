package io.github.potjerodekool.nabu.compiler.backend.ir;

public abstract class INode {

    private int lineNumber = -1;

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(final int lineNumber) {
        this.lineNumber = lineNumber;
    }
}
