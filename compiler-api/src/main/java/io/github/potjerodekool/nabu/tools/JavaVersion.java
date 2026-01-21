package io.github.potjerodekool.nabu.tools;

import java.util.Arrays;

/**
 * Enumeration of Java class file versions.
 */
public enum JavaVersion {
    V17("17", 61),
    V18("18", 62),
    V19("19", 63),
    V20("20", 64),
    V21("21", 65),
    V22("22", 66),
    V23("23", 67);

    //Minimal supported version.
    public static final JavaVersion MINIMAL_VERSION = V17;

    //Maximal supported version.
    public static final JavaVersion MAXIMAL_VERSION = V23;

    private final String versionName;

    private final int value;

    JavaVersion(final String versionName,
                final int value) {
        this.versionName = versionName;
        this.value = value;
    }

    /**
     * @return Returns the class file version.
     */
    public int getValue() {
        return value;
    }

    /**
     * @param name version name. For example 17 for Java 17, 18 for Java 18, etc.
     * @return Returns the version name matching the name or else returning the minimal version.
     */
    public static JavaVersion parseFromName(final String name) {
        return Arrays.stream(values())
                .filter(it -> it.versionName.equals(name))
                .findFirst()
                .orElse(MINIMAL_VERSION);
    }
}