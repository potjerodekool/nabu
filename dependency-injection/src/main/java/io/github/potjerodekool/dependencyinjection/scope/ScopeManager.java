package io.github.potjerodekool.dependencyinjection.scope;

import io.github.potjerodekool.dependencyinjection.ApplicationContext;

public interface ScopeManager<T> {

    Class<T> getBeanType();

    T get(ApplicationContext applicationContext);

}
