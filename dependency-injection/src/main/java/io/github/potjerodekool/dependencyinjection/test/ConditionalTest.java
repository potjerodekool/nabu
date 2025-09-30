package io.github.potjerodekool.dependencyinjection.test;

import io.github.potjerodekool.dependencyinjection.bean.BeanDefinition;

import java.lang.annotation.Annotation;

public interface ConditionalTest<C extends Annotation> {

    boolean test(C condition,
                 BeanDefinition<?> beanDefinition);
}
