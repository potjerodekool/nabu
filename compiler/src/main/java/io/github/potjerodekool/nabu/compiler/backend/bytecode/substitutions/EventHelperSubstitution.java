package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

/**
 * {@code jdk.internal.event.EventHelper}: the JDK logs security events through Flight Recorder, which a
 * native executable does not have. Its callers only log after {@code isLoggingSecurity()}, so saying no is enough.
 */
public final class EventHelperSubstitution {

    private EventHelperSubstitution() {
    }

    public static boolean isLoggingSecurity() {
        return false;
    }
}
