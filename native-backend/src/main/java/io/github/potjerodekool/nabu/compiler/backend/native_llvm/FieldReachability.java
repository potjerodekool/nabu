package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which instance fields does the program ever read? A field that is only written (or never touched) takes room in
 * every object for nothing. The scan is conservative: a field value that occurs anywhere but as the target of a store
 * counts as a read, whatever the instruction.
 *
 * <p>What this cannot see: access by name at run time (reflection, {@code Unsafe.objectFieldOffset}, field
 * updaters). The caller decides what to keep for those.</p>
 */
final class FieldReachability {

    /** {@code -Dnabu.deadFields=false} keeps every instance field in the object struct (dead-field elimination off). */
    static boolean eliminate() {
        return !"false".equals(System.getProperty("nabu.deadFields"));
    }

    /** Instance fields that are read somewhere, as "owner-internal-name#name" of the class that names them. */
    final Set<String> read = new HashSet<>();
    /** Instance fields that are written somewhere. */
    final Set<String> written = new HashSet<>();
    /**
     * The simple names of every instance field that is read anywhere, regardless of the class that declares it.
     *
     * <p>The conservative key for elimination: a name that is read somewhere is never left out of a struct,
     * whichever class declares it. That also covers access by name at run time (the runtime reaches fields such as
     * {@code String.value} and {@code File.path} through {@code nabu_instance_field_offset}, not through an IR
     * field access).</p>
     */
    final Set<String> readNames = new HashSet<>();

    private static final Map<Class<?>, Method[]> ACCESSORS = new HashMap<>();

    static FieldReachability analyze(final Collection<IRModule> modules) {
        final FieldReachability result = new FieldReachability();
        for (final IRModule module : modules) {
            for (final IRFunction function : module.functions()) {
                if (function.isExternal()) {
                    continue;
                }
                for (final var block : function.blocks()) {
                    for (final IRInstruction instruction : block.instructions()) {
                        result.scan(instruction);
                    }
                }
            }
            // an initializer of a field (constant values) is not a read
        }
        return result;
    }

    private void scan(final IRInstruction instruction) {
        if (instruction instanceof IRInstruction.Store store) {
            // the target of a store is a write; the value that is stored can be a field value (a read)
            final String target = fieldKey(store.ptr());
            if (target != null) {
                written.add(target);
                visitOperandsOfFieldPointer(store.ptr());
            } else {
                visit(store.ptr());
            }
            visit(store.value());
            return;
        }
        visit(instruction);
    }

    /** A field pointer is Values(receiver..., Named): the receiver expressions are reads of their own fields. */
    private void visitOperandsOfFieldPointer(final IRValue pointer) {
        if (pointer instanceof IRValue.Values values) {
            for (int i = 0; i < values.values().size() - 1; i++) {
                visit(values.values().get(i));
            }
        }
    }

    private void visit(final Object node) {
        if (node == null) {
            return;
        }
        if (node instanceof IRValue.Values values) {
            final String key = fieldKey(values);
            if (key != null) {
                read.add(key);
                readNames.add(((IRValue.Named) values.values().getLast()).name());
            }
            for (final IRValue value : values.values()) {
                if (!(value instanceof IRValue.Named)) {
                    visit(value);
                }
            }
            return;
        }
        if (node instanceof IRValue.Named named) {
            if (!named.isStatic()) {
                readNames.add(named.name());
                if (named.ownerType() != null) {
                    read.add(keyOf(named));
                }
            }
            return;
        }
        if (node instanceof List<?> list) {
            for (final Object each : list) {
                visit(each);
            }
            return;
        }
        if (node instanceof IRType || node instanceof String || node instanceof Number || node instanceof Boolean
                || node instanceof Enum<?> || node instanceof io.github.potjerodekool.nabu.debug.SourceLocation) {
            return;
        }
        if (node.getClass().isRecord()) {
            for (final Method accessor : accessorsOf(node.getClass())) {
                try {
                    visit(accessor.invoke(node));
                } catch (final ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static Method[] accessorsOf(final Class<?> type) {
        synchronized (ACCESSORS) {
            return ACCESSORS.computeIfAbsent(type, t -> {
                final List<Method> methods = new ArrayList<>();
                for (final RecordComponent component : t.getRecordComponents()) {
                    methods.add(component.getAccessor());
                }
                for (final Method method : methods) {
                    method.setAccessible(true);
                }
                return methods.toArray(new Method[0]);
            });
        }
    }

    private static String fieldKey(final IRValue value) {
        if (value instanceof IRValue.Values values && !values.values().isEmpty()
                && values.values().getLast() instanceof IRValue.Named named
                && !named.isStatic() && named.ownerType() != null) {
            return keyOf(named);
        }
        return null;
    }

    private static String keyOf(final IRValue.Named named) {
        final String owner = named.ownerType() instanceof IRType.Ptr ptr && ptr.jvmDescriptor() != null
                ? ptr.jvmDescriptor() : String.valueOf(named.ownerType());
        return owner + "#" + named.name();
    }

    /**
     * Whether any instance field with this simple name is read somewhere. The conservative test for leaving a field
     * out of the object struct: a name that is read anywhere stays in every class that declares it.
     */
    boolean isRead(final String name) {
        return readNames.contains(name);
    }

    /** Prints how many instance fields are never read, JDK and other (-Dnabu.closureStats). */
    static void report(final Collection<IRModule> modules, final FieldReachability reach) {
        // a read through a subclass names the subclass: match by field name over the class and its descendants
        final Set<String> readNames = reach.readNames;
        long[] fields = new long[2];
        long[] unread = new long[2];
        long[] bytes = new long[2];
        long[] unreadBytes = new long[2];
        final Map<String, long[]> byPackage = new HashMap<>();
        for (final IRModule module : modules) {
            final String name = module.name.replace('.', '/');
            final int kind = isJdk(name) ? 0 : 1;
            for (final IRField field : module.fields()) {
                if (Flags.hasFlag(field.flags(), Flags.STATIC)) {
                    continue;
                }
                final long size = sizeOf(field.type());
                fields[kind]++;
                bytes[kind] += size;
                if (!readNames.contains(field.name())) {
                    unread[kind]++;
                    unreadBytes[kind] += size;
                    final String pkg = packageOf(module.name);
                    final long[] entry = byPackage.computeIfAbsent(pkg, k -> new long[2]);
                    entry[0]++;
                    entry[1] += size;
                }
            }
        }
        System.err.println("[closureStats] instance fields JDK: " + fields[0] + " (" + bytes[0] + " B per instance), name never read: "
                + unread[0] + " (" + unreadBytes[0] + " B); other: " + fields[1] + " (" + bytes[1] + " B), name never read: "
                + unread[1] + " (" + unreadBytes[1] + " B)");
        byPackage.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(12)
                .forEach(e -> System.err.println("[closureStats]   unread " + e.getKey() + ": " + e.getValue()[0]
                        + " fields, " + e.getValue()[1] + " B"));
    }

    private static boolean isJdk(final String internalName) {
        return internalName.startsWith("java/") || internalName.startsWith("javax/") || internalName.startsWith("jdk/")
                || internalName.startsWith("sun/") || internalName.startsWith("com/sun/");
    }

    private static String packageOf(final String name) {
        final String[] parts = name.split("\\.");
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, parts.length - 1); i++) {
            sb.append(i == 0 ? "" : ".").append(parts[i]);
        }
        return sb.toString();
    }

    private static long sizeOf(final IRType type) {
        if (type instanceof IRType.Int i) {
            return Math.max(1, i.bits() / 8);
        }
        if (type instanceof IRType.Float f) {
            return Math.max(4, f.bits() / 8);
        }
        return 8;
    }
}
