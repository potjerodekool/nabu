package io.github.potjerodekool.nabu.compiler.backend.native_llvm.gc;

import io.github.potjerodekool.nabu.backend.CompileOptions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/** The collectors a build can choose from: the ones that come with the backend and those that a service file lists. */
public final class GarbageCollectors {

    private static final Map<String, GarbageCollector> BY_NAME = new LinkedHashMap<>();

    static {
        register(new BoehmGarbageCollector());
        register(new NoGarbageCollector());
        for (final GarbageCollector collector : ServiceLoader.load(GarbageCollector.class)) {
            register(collector);
        }
    }

    private GarbageCollectors() {
    }

    /** Makes a collector available under its name (a later one with the same name replaces the earlier). */
    public static synchronized void register(final GarbageCollector collector) {
        BY_NAME.put(collector.name(), collector);
    }

    /** The collector of a strategy; the name of the strategy in lower case is the name of the collector. */
    public static synchronized GarbageCollector of(final CompileOptions.GcStrategy strategy) {
        final GarbageCollector collector = BY_NAME.get(strategy.name().toLowerCase());
        if (collector == null) {
            throw new IllegalStateException("No garbage collector named '" + strategy.name().toLowerCase()
                    + "'; available: " + BY_NAME.keySet());
        }
        return collector;
    }
}
