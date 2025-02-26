package io.github.potjerodekool.dependencyinjection.scope;

import io.github.potjerodekool.dependencyinjection.ApplicationContext;
import io.github.potjerodekool.dependencyinjection.bean.BeanDefinition;

public class LazySingletonScopeManager<T> implements ScopeManager<T> {

    private final BeanDefinition<T> beanDefinition;
    private T instance = null;
    private boolean initialized = false;

    public LazySingletonScopeManager(final BeanDefinition<T> beanDefinition) {
        this.beanDefinition = beanDefinition;
    }

    @Override
    public Class<T> getBeanType() {
        return this.beanDefinition.getBeanType();
    }

    @Override
    public T get(final ApplicationContext applicationContext) {
        if (this.initialized) {
            return instance;
        }

        synchronized (this) {
            if (!this.initialized) {
                this.initialized = true;
                this.instance = applicationContext.createBean(beanDefinition);
                applicationContext.registerBean(getBeanType(), this.instance);
            }
        }

        return this.instance;
    }
}
