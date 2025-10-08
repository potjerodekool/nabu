package io.github.potjerodekool.nabu.compiler.backend.ir;


import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import java.util.Objects;

public final class Local {
    private final String name;
    private final IType type;
    private final int index;
    private final boolean parameter;
    private ILabel start;
    private ILabel end;

    public Local(final String name,
                 final IType type,
                 final int index,
                 final boolean parameter) {
        Objects.requireNonNull(type);
        this.name = name;
        this.type = type;
        this.index = index;
        this.parameter = parameter;
    }

    public String name() {
        return name;
    }

    public IType type() {
        return type;
    }

    public int index() {
        return index;
    }

    public boolean parameter() {
        return parameter;
    }

    public ILabel getStart() {
        return start;
    }

    public void setStart(final ILabel start) {
        this.start = start;
    }

    public ILabel getEnd() {
        return end;
    }

    public void setEnd(final ILabel end) {
        this.end = end;
    }
}
