package io.github.potjerodekool.nabu.tree;

import java.util.Arrays;

/**
 * An enumeration of tags that can be applied to trees.
 */
public enum Tag {
    EQ("=="),
    NE("!="),
    LT("<"),
    GT(">"),
    LE("<="),
    GE(">="),
    AND("&&"),
    OR("||"),
    NOT("!"),
    ADD("+"),
    SUB("-"),
    POST_INC,
    POST_DEC,
    ASSIGN("="),
    ADD_ASSIGN("+="),
    MUL_ASSIGN("*="),
    DIV_ASSIGN("/="),
    AND_ASSIGN("&="),
    OR_ASSIGN("|="),
    XOR_ASSIGN("^="),
    MOD_ASSIGN("%="),
    LSHIFT_ASSIGN("<<="),
    RSHIFT_ASSIGN(">>="),
    URSHIFT_ASSIGN(">>>=");

    private final String text;

    Tag() {
        this.text = null;
    }

    Tag(final String text) {
        this.text = text;
    }

    public boolean isPrefix() {
        return this != POST_INC && this != POST_DEC;
    }

    public String getText() {
        return text;
    }

    public static Tag fromText(final String text) {
        return Arrays.stream(values())
                .filter(it -> it.text != null)
                .filter(it -> it.text.equals(text))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(text));
    }

    @Override
    public String toString() {
        return text != null
                ? text
                : name();
    }
}
