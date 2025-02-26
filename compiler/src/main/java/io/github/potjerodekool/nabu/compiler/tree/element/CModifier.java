package io.github.potjerodekool.nabu.compiler.tree.element;

public enum CModifier {
    ABSTRACT(false),
    PUBLIC(true),
    PRIVATE(true),
    PROTECTED(true),
    STATIC(false),
    FINAL(false);

    private final boolean isAccessModifier;

    CModifier(final boolean isAccessModifier) {
        this.isAccessModifier = isAccessModifier;
    }

    public boolean isAccessModifier() {
        return isAccessModifier;
    }

    public static CModifier parse(final String modifier) {
        return CModifier.valueOf(modifier.toUpperCase());
    }
}
