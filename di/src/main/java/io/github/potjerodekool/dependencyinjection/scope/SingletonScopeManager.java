package io.github.potjerodekool.dependencyinjection.scope;

import io.github.potjerodekool.dependencyinjection.ApplicationContext;

public class SingletonScopeManager<T> implements ScopeManager<T> {

    private final Object instance;

    public SingletonScopeManager(final Object instance) {
        this.instance = instance;
    }

    @Override
    public Class<T> getBeanType() {
        return (Class<T>) instance.getClass();
    }

    @Override
    public T get(final ApplicationContext applicationContext) {
        return (T) instance;
    }
}
