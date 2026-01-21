package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import javax.lang.model.element.Name;

public class JName implements Name {

    private final String value;

    public JName(final String value) {
        this.value = value;
    }

    @Override
    public boolean contentEquals(final CharSequence cs) {
        return value.contentEquals(cs);
    }

    @Override
    public int length() {
        return value.length();
    }

    @Override
    public char charAt(final int index) {
        return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(final int start, final int end) {
        return value.subSequence(start, end);
    }

    @Override
    public String toString() {
        return value;
    }
}
