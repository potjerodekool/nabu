package io.github.potjerodekool.nabu.backend.ir.dataflow;

import io.github.potjerodekool.nabu.backend.ir.IRFunction;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Eenvoudige cache voor dataflow-resultaten per functie.
 * Gebruikt een WeakHashMap zodat functies vrijgegeven kunnen worden.
 */
public final class DataFlowCache {

    private static final Map<IRFunction, Map<Class<?>, Object>> CACHE =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private DataFlowCache() {}

    @SuppressWarnings("unchecked")
    public static <V> DataFlowResult<V> get(IRFunction function, Class<?> analysisClass) {
        final var inner = CACHE.get(function);
        if (inner == null) return null;
        final Object r = inner.get(analysisClass);
        return (r instanceof DataFlowResult<?> dfr) ? (DataFlowResult<V>) dfr : null;
    }

    public static void put(IRFunction function, Class<?> analysisClass, DataFlowResult<?> result) {
        CACHE.computeIfAbsent(function, k -> new IdentityHashMap<>())
                .put(analysisClass, result);
    }

    public static void invalidate(IRFunction function) {
        CACHE.remove(function);
    }

    public static void clear() {
        CACHE.clear();
    }
}