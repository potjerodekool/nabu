package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * The method handles that libraries make of reflection objects ({@code Lookup.unreflect}, {@code unreflectGetter},
 * {@code unreflectSetter}, {@code unreflectConstructor}: Jackson 3 reads and writes the properties of a bean with
 * them) in a native executable, where {@code MethodHandles.lookup()} is null and the JDK's handles do not exist. A
 * handle is a {@link Handle} here (typed as MethodHandle by the code that uses it); the calls on it are redirected
 * to this class (see RuntimeCalls) and {@code invoke}/{@code invokeExact}, which are signature polymorphic, are
 * rewritten to {@link #invoke} by MethodHandleRewriter. Written for bytecode lowering: no string concatenation with
 * {@code +} and no lambdas.
 */
public final class MethodHandleSupport {

    private MethodHandleSupport() {
    }

    private static final Object[] NONE = new Object[0];

    private static final int METHOD = 0;
    private static final int GETTER = 1;
    private static final int SETTER = 2;
    private static final int CONSTRUCTOR = 3;

    private static final class Handle {
        final int kind;
        final Object member;
        final Object[] bound;

        Handle(final int kind, final Object member, final Object[] bound) {
            this.kind = kind;
            this.member = member;
            this.bound = bound;
        }
    }

    public static Object unreflect(final Object lookup, final Method method) {
        return new Handle(METHOD, method, NONE);
    }

    public static Object unreflectGetter(final Object lookup, final Field field) {
        return new Handle(GETTER, field, NONE);
    }

    public static Object unreflectSetter(final Object lookup, final Field field) {
        return new Handle(SETTER, field, NONE);
    }

    public static Object unreflectConstructor(final Object lookup, final Constructor<?> constructor) {
        return new Handle(CONSTRUCTOR, constructor, NONE);
    }

    /** {@code asType} and {@code asFixedArity}: the conversions happen when the handle is invoked. */
    public static Object asType(final Object handle, final Object type) {
        return handle;
    }

    public static Object asFixedArity(final Object handle) {
        return handle;
    }

    public static Object bindTo(final Object handle, final Object value) {
        final Handle h = (Handle) handle;
        final Object[] bound = new Object[h.bound.length + 1];
        System.arraycopy(h.bound, 0, bound, 0, h.bound.length);
        bound[h.bound.length] = value;
        return new Handle(h.kind, h.member, bound);
    }

    public static Object invokeWithArguments(final Object handle, final Object[] arguments) throws Throwable {
        return invoke(handle, arguments);
    }

    public static String describe(final Object handle) {
        final Handle h = (Handle) handle;
        return h.member.toString();
    }

    /** Calls the handle: the bound arguments first, then these. */
    public static Object invoke(final Object handle, final Object[] arguments) throws Throwable {
        final Handle h = (Handle) handle;
        final Object[] all = new Object[h.bound.length + arguments.length];
        System.arraycopy(h.bound, 0, all, 0, h.bound.length);
        System.arraycopy(arguments, 0, all, h.bound.length, arguments.length);
        try {
            switch (h.kind) {
                case METHOD: {
                    final Method method = (Method) h.member;
                    if (Modifier.isStatic(method.getModifiers())) {
                        return ProxySupport.invoke(method, null, all);
                    }
                    final Object[] rest = new Object[all.length - 1];
                    System.arraycopy(all, 1, rest, 0, rest.length);
                    return ProxySupport.invoke(method, all[0], rest);
                }
                case GETTER: {
                    final Field field = (Field) h.member;
                    return Modifier.isStatic(field.getModifiers()) ? field.get(null) : field.get(all[0]);
                }
                case SETTER: {
                    final Field field = (Field) h.member;
                    if (Modifier.isStatic(field.getModifiers())) {
                        field.set(null, all[0]);
                    } else {
                        field.set(all[0], all[1]);
                    }
                    return null;
                }
                default:
                    return ReflectSupport.newInstance((Constructor<?>) h.member, all);
            }
        } catch (final InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }
}
