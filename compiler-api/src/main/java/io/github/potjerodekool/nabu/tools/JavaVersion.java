package io.github.potjerodekool.nabu.tools;

import java.util.Arrays;

public enum JavaVersion {
    V17(61),
    V18(62),
    V19(63),
    V20(64),
    V21(65),
    V22(66),
    V23(67);

    public static final JavaVersion MINIMAL_VERSION = V17;

    private final int value;

    JavaVersion(final int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static JavaVersion parseFromName(final String name) {
        return Arrays.stream(values())
                .filter(it -> it.name().equals(name))
                .findFirst()
                .orElse(MINIMAL_VERSION);
    }
}