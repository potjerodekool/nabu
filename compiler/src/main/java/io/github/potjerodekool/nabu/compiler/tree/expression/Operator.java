package io.github.potjerodekool.nabu.compiler.tree.expression;

import java.util.Arrays;

public enum Operator {
    EQ("=="),
    NOT_EQ("!="),
    LT("<"),
    GT(">"),
    LE("<="),
    GE(">="),
    AND("&&"),
    OR("||"),
    BANG("!");

    private final String text;

    Operator(final String text) {
        this.text = text;
    }

    public static Operator fromText(final String text) {
        return Arrays.stream(values())
                .filter(it -> it.text.equals(text))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(text));
    }

    public String getText() {
        return text;
    }
}
