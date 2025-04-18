package io.github.potjerodekool.nabu;

public enum SomeState {
    ON("On"),
    OFF("Off");

    private final String text;

    SomeState(final String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }
}
