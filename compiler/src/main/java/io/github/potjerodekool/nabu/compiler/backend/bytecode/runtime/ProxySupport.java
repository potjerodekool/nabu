package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Dynamic proxies in a native executable (compare GraalVM: the proxy classes
 * exist at build time, for the interface lists in {@code proxy-config.json}).
 * Calls to {@code Proxy.newProxyInstance}, {@code Proxy.getInvocationHandler},
 * {@code Proxy.isProxyClass} and {@code Method.invoke} are redirected here
 * (see {@code RuntimeCalls}); the generated {@link ProxyClasses} creates the
 * proxies and calls interface methods for {@code Method.invoke}.
 *
 * <p>Written for bytecode lowering: no string concatenation with {@code +}
 * and no lambdas.
 */
public final class ProxySupport {

    private static final Map<String, Method> METHODS = new HashMap<>();

    private ProxySupport() {
    }

    /** The C runtime's Method for the method {@code name descriptor} of {@code declaring}. */
    private static native Method method0(Class<?> declaring, String name, String descriptor);

    /** "owner#name#descriptor" of a Method made by {@link #method0}, else null. */
    private static native String methodKey(Method method);

    /** The Method a proxy passes to its handler: one object per method. */
    public static Method method(final Class<?> declaring, final String name, final String descriptor) {
        final String key = declaring.getName().concat("#").concat(name).concat("#").concat(descriptor);
        synchronized (METHODS) {
            Method method = METHODS.get(key);
            if (method == null) {
                method = method0(declaring, name, descriptor);
                METHODS.put(key, method);
            }
            return method;
        }
    }

    public static Object newProxyInstance(final ClassLoader loader, final Class<?>[] interfaces,
                                          final InvocationHandler h) {
        if (h == null) {
            throw new NullPointerException();
        }
        final StringBuilder key = new StringBuilder();
        for (int i = 0; i < interfaces.length; i++) {
            if (i > 0) {
                key.append(',');
            }
            key.append(interfaces[i].getName());
        }
        final Object proxy = ProxyClasses.create(key.toString(), h);
        if (proxy == null) {
            throw new IllegalArgumentException(new StringBuilder("No proxy class for the interfaces [")
                    .append(key).append("]: add them to a proxy-config.json under META-INF/native-image")
                    .toString());
        }
        return proxy;
    }

    public static InvocationHandler getInvocationHandler(final Object proxy) {
        if (proxy instanceof ProxyBase base) {
            return base.h;
        }
        throw new IllegalArgumentException("not a proxy instance");
    }

    public static boolean isProxyClass(final Class<?> type) {
        return ProxyClasses.isProxyClass(type.getName());
    }

    /**
     * {@code Method.invoke}: a method of a proxied interface (as a handler
     * gets it) is called on the target through the generated dispatcher;
     * any other Method goes to the C runtime as before.
     */
    public static Object invoke(final Method method, final Object target, final Object[] args)
            throws IllegalAccessException, InvocationTargetException {
        final String key = methodKey(method);
        if (key == null) {
            return method.invoke(target, args);
        }
        if (target == null) {
            throw new NullPointerException();
        }
        try {
            return ProxyClasses.invoke(key, target, args == null ? new Object[0] : args);
        } catch (final InvocationTargetException | RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
