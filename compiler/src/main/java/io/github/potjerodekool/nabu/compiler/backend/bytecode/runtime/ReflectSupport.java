package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * {@code Class.getMethods}, {@code getDeclaredMethods}, {@code getMethod},
 * {@code getDeclaredMethod}, the corresponding constructor lookups and
 * {@code Constructor.newInstance} in a native executable (calls to them are
 * redirected here, see {@code RuntimeCalls}): the methods and constructors
 * come from the generated {@link ReflectionData}, for the classes and members
 * of the reflection configuration, as with GraalVM {@code native-image}.
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

    /*
     * Constructors: from the generated data for a class of the reflection
     * configuration; for any other class from the C runtime as before (its
     * no-argument constructor, which the JDK instantiates reflectively).
     */

    public static Constructor<?>[] getConstructors(final Class<?> type) {
        final Constructor<?>[] all = ReflectionData.constructors(type.getName());
        if (all == null) {
            return runtimeConstructors(type);
        }
        int count = 0;
        for (final Constructor<?> constructor : all) {
            if (Modifier.isPublic(constructor.getModifiers())) {
                count++;
            }
        }
        final Constructor<?>[] result = new Constructor<?>[count];
        int i = 0;
        for (final Constructor<?> constructor : all) {
            if (Modifier.isPublic(constructor.getModifiers())) {
                result[i++] = constructor;
            }
        }
        return result;
    }

    public static Constructor<?>[] getDeclaredConstructors(final Class<?> type) {
        final Constructor<?>[] all = ReflectionData.constructors(type.getName());
        if (all == null) {
            return runtimeConstructors(type);
        }
        final Constructor<?>[] copy = new Constructor<?>[all.length];
        System.arraycopy(all, 0, copy, 0, all.length);
        return copy;
    }

    public static Constructor<?> getConstructor(final Class<?> type, final Class<?>[] parameterTypes)
            throws NoSuchMethodException {
        final Constructor<?>[] all = ReflectionData.constructors(type.getName());
        if (all == null) {
            return type.getConstructor(parameterTypes);
        }
        return findConstructor(type, getConstructors(type), parameterTypes);
    }

    public static Constructor<?> getDeclaredConstructor(final Class<?> type, final Class<?>[] parameterTypes)
            throws NoSuchMethodException {
        final Constructor<?>[] all = ReflectionData.constructors(type.getName());
        if (all == null) {
            return type.getDeclaredConstructor(parameterTypes);
        }
        return findConstructor(type, all, parameterTypes);
    }

    /** {@code Constructor.newInstance}: a registered constructor through the dispatcher, else the C runtime. */
    public static Object newInstance(final Constructor<?> constructor, final Object[] args)
            throws InstantiationException, IllegalAccessException, InvocationTargetException {
        final String key = ProxySupport.key(constructor);
        if (key == null) {
            return constructor.newInstance(args);
        }
        // The dispatcher throws InstantiationException for an abstract class.
        return ProxySupport.call(key, null, args);
    }

    /** The C runtime's view: the no-argument constructor, if the class has one in its table. */
    private static Constructor<?>[] runtimeConstructors(final Class<?> type) {
        try {
            final Constructor<?>[] result = new Constructor<?>[1];
            result[0] = type.getDeclaredConstructor();
            return result;
        } catch (final NoSuchMethodException e) {
            return new Constructor<?>[0];
        }
    }

    private static Constructor<?> findConstructor(final Class<?> type, final Constructor<?>[] constructors,
                                                  final Class<?>[] parameterTypes) throws NoSuchMethodException {
        final Class<?>[] wanted = parameterTypes == null ? new Class<?>[0] : parameterTypes;
        for (final Constructor<?> constructor : constructors) {
            if (sameTypes(constructor.getParameterTypes(), wanted)) {
                return constructor;
            }
        }
        throw new NoSuchMethodException(signature(type.getName(), "<init>", wanted));
    }

    private static String signature(final String className, final String name, final Class<?>[] types) {
        final StringBuilder message = new StringBuilder(className).append('.').append(name).append('(');
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                message.append(", ");
            }
            message.append(types[i] == null ? "null" : types[i].getName());
        }
        return message.append(')').toString();
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
        throw new NoSuchMethodException(signature(type.getName(), name, wanted));
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
