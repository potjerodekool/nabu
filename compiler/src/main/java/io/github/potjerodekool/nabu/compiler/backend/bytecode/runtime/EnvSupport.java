package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code System.getenv()} for native programs: the JDK reads the environment through a native block of
 * {@code ProcessEnvironment}; here the C runtime hands out the {@code NAME=value} pairs.
 */
public final class EnvSupport {

    private static Map<String, String> environment;

    private EnvSupport() {
    }

    /** The environment as {@code NAME=value} strings. */
    private static native String[] environ0();

    public static synchronized Map<String, String> getenv() {
        if (environment == null) {
            final Map<String, String> map = new HashMap<>();
            for (final String pair : environ0()) {
                final int eq = pair.indexOf('=');
                if (eq > 0) {
                    map.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
            environment = Collections.unmodifiableMap(map);
        }
        return environment;
    }
}
