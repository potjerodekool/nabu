package io.github.potjerodekool.dependencyinjection.bean;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

public class BeanDefinition<T> {

    private final Object autoConfigInstance;
    private final String methodName;
    private final Class<T> beanType;
    private final Map<Class<?>, Annotation> annotationMap;
    private final String scope;

    public BeanDefinition(final Object autoConfigInstance,
                          final String methodName,
                          final Class<T> beanType,
                          final Map<Class<?>, Annotation> annotationMap,
                          final String scope) {
        this.autoConfigInstance = autoConfigInstance;
        this.methodName = methodName;
        this.beanType = beanType;
        this.annotationMap = annotationMap;
        this.scope = scope;
    }

    public Object getAutoConfigInstance() {
        return autoConfigInstance;
    }

    public Map<Class<?>, Annotation> getAnnotations() {
        return annotationMap;
    }

    public Class<T> getBeanType() {
        return beanType;
    }

    public Method getBeanMethod() {
        return Arrays.stream(autoConfigInstance.getClass().getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .orElse(null);
    }

    public String getScope() {
        return scope;
    }
}
