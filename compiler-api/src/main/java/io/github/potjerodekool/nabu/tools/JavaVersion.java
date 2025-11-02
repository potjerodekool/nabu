package io.github.potjerodekool.nabu.tools;

import java.util.Arrays;

/**
 * Enumeration of Java class file versions.
 */
public enum JavaVersion {
    V17(61),
    V18(62),
    V19(63),
    V20(64),
    V21(65),
    V22(66),
    V23(67);

    //Minimal supported version.
    public static final JavaVersion MINIMAL_VERSION = V17;

    //Maximal supported version.
    public static final JavaVersion MAXIMAL_VERSION = V23;

    private final int value;

    JavaVersion(final int value) {
        this.value = value;
    }

    /**
    * @return Returns the class file version.
     */
    public int getValue() {
        return value;
    }

    /**
     * @param name version name. For example V17 for Java 17.
     * @return Returns the version name matching the name or else returning the minimal version.
     */
    public static JavaVersion parseFromName(final String name) {
        return Arrays.stream(values())
                .filter(it -> it.name().equals(name))
                .findFirst()
                .orElse(MINIMAL_VERSION);
    }
}