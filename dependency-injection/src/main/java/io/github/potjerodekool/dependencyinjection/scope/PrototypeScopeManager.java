package io.github.potjerodekool.dependencyinjection.scope;

import io.github.potjerodekool.dependencyinjection.ApplicationContext;
import io.github.potjerodekool.dependencyinjection.bean.BeanDefinition;

public class PrototypeScopeManager<T> implements ScopeManager<T> {

    private final BeanDefinition<T> beanDefinition;
    private final Class<T> beanType;

    public PrototypeScopeManager(final BeanDefinition<T> beanDefinition) {
        this.beanDefinition = beanDefinition;
        this.beanType = beanDefinition.getBeanType();
    }


    @Override
    public Class<T> getBeanType() {
        return beanType;
    }

    @Override
    public T get(final ApplicationContext applicationContext) {
        return applicationContext.createBean(beanDefinition);
    }
}
