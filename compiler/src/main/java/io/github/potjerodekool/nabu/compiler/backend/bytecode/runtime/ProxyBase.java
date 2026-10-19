package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.lang.reflect.InvocationHandler;

/**
 * Superclass of the dynamic proxy classes the compiler generates for a
 * native executable (see {@code ProxyClassGenerator}): it holds the
 * invocation handler. A JDK proxy extends {@code java.lang.reflect.Proxy},
 * which the C runtime models and which is not compiled.
 */
public abstract class ProxyBase {

    final InvocationHandler h;

    protected ProxyBase(final InvocationHandler h) {
        if (h == null) {
            throw new NullPointerException();
        }
        this.h = h;
    }
}
