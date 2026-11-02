package io.github.potjerodekool.nabu.tools;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Enumeration of Java versions (language level and class file version).
 */
public enum JavaVersion {
    V17("17", 61),
    V18("18", 62),
    V19("19", 63),
    V20("20", 64),
    V21("21", 65),
    V22("22", 66),
    V23("23", 67),
    V24("24", 68),
    V25("25", 69);

    //Minimal supported version.
    public static final JavaVersion MINIMAL_VERSION = V17;

    //Maximal supported version.
    public static final JavaVersion MAXIMAL_VERSION = V25;

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
     * @return Returns the feature number, for example 17 for Java 17.
     */
    public int getFeature() {
        return Integer.parseInt(versionName);
    }

    /**
     * @param other Another version.
     * @return Returns true if this version is the same as or newer than the other version.
     */
    public boolean isAtLeast(final JavaVersion other) {
        return compareTo(other) >= 0;
    }

    @Override
    public String toString() {
        return versionName;
    }

    /**
     * @param name version name. For example 17 for Java 17, 18 for Java 18, etc.
     * @return Returns the version name matching the name or else returning the minimal version.
     */
    public static JavaVersion parseFromName(final String name) {
        return find(name).orElse(MINIMAL_VERSION);
    }

    /**
     * @param name version name. For example 17 for Java 17, 18 for Java 18, etc.
     * @return Returns the version with that name, or empty if it is not a supported version.
     */
    public static Optional<JavaVersion> find(final String name) {
        return Arrays.stream(values())
                .filter(it -> it.versionName.equals(name))
                .findFirst();
    }

    /**
     * @param name version name. For example 17 for Java 17, 18 for Java 18, etc.
     * @return Returns the version with that name.
     * @throws IllegalArgumentException If it is not a supported version.
     */
    public static JavaVersion parseStrict(final String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException(unsupportedMessage("version", name)));
    }

    /**
     * @param option The name of the option.
     * @param value The unsupported value.
     * @return Returns the message for an unsupported value.
     */
    public static String unsupportedMessage(final String option,
                                            final String value) {
        return "Unsupported value '" + value + "' for " + option + ". Supported versions: "
                + Arrays.stream(values()).map(it -> it.versionName).collect(Collectors.joining(", "));
    }
}
