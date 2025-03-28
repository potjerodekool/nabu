package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.internal.Flags;

public enum Modifier {
    PUBLIC(Flags.PUBLIC),
    PRIVATE(Flags.PRIVATE),
    PROTECTED(Flags.PROTECTED),
    FINAL(Flags.FINAL),
    ABSTRACT(Flags.ABSTRACT),
    STATIC(Flags.STATIC),
    TRANSIENT(Flags.TRANSIENT),
    VOLATILE(Flags.VOLATILE),
    SYNTHETIC(Flags.SYNTHETIC),
    NATIVE(Flags.NATIVE);

    private final long flag;

    Modifier(final long flag) {
        this.flag = flag;
    }

    public long getFlag() {
        return flag;
    }
}
