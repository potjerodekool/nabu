package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.lang.reflect.Method;

/**
 * {@code Class.getMethods}, {@code getDeclaredMethods}, {@code getMethod}
 * and {@code getDeclaredMethod} in a native executable (calls to them are
 * redirected here, see {@code RuntimeCalls}): the methods come from the
 * generated {@link ReflectionData}, for the classes and methods of the
 * reflection configuration, as with GraalVM {@code native-image}.
 *
 * <p>Written for bytecode lowering: no string concatenation with {@code +}
 * and no lambdas.
 */
public final class ReflectSupport {

    private ReflectSupport() {
    }

    public static Method[] getMethods(final Class<?> type) {
        return copy(ReflectionData.methods(type.getName(), true));
    }

    public static Method[] getDeclaredMethods(final Class<?> type) {
        return copy(ReflectionData.methods(type.getName(), false));
    }

    public static Method getMethod(final Class<?> type, final String name, final Class<?>[] parameterTypes)
            throws NoSuchMethodException {
        return find(type, ReflectionData.methods(type.getName(), true), name, parameterTypes);
    }

    public static Method getDeclaredMethod(final Class<?> type, final String name, final Class<?>[] parameterTypes)
            throws NoSuchMethodException {
        return find(type, ReflectionData.methods(type.getName(), false), name, parameterTypes);
    }

    private static Method[] copy(final Method[] methods) {
        if (methods == null) {
            return new Method[0];
        }
        final Method[] copy = new Method[methods.length];
        System.arraycopy(methods, 0, copy, 0, methods.length);
        return copy;
    }

    private static Method find(final Class<?> type, final Method[] methods, final String name,
                               final Class<?>[] parameterTypes) throws NoSuchMethodException {
        if (name == null) {
            throw new NullPointerException();
        }
        final Class<?>[] wanted = parameterTypes == null ? new Class<?>[0] : parameterTypes;
        if (methods != null) {
            for (final Method method : methods) {
                if (method.getName().equals(name) && sameTypes(method.getParameterTypes(), wanted)) {
                    return method;
                }
            }
        }
        final StringBuilder message = new StringBuilder(type.getName()).append('.').append(name).append('(');
        for (int i = 0; i < wanted.length; i++) {
            if (i > 0) {
                message.append(", ");
            }
            message.append(wanted[i] == null ? "null" : wanted[i].getName());
        }
        throw new NoSuchMethodException(message.append(')').toString());
    }

    private static boolean sameTypes(final Class<?>[] actual, final Class<?>[] wanted) {
        if (actual.length != wanted.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            if (wanted[i] == null || !actual[i].getName().equals(wanted[i].getName())) {
                return false;
            }
        }
        return true;
    }
}
