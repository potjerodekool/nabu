package io.github.potjerodekool.dependencyinjection;

import io.github.potjerodekool.dependencyinjection.bean.BeanDefinition;
import io.github.potjerodekool.dependencyinjection.scope.*;
import io.github.potjerodekool.dependencyinjection.test.ConditionalTest;

import java.beans.ConstructorProperties;
import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.util.*;
import java.util.stream.Collectors;

public class ApplicationContext {

    private final Map<Class<?>, List<ScopeManager<?>>> beansMap = new HashMap<>();
    private final Map<Class<?>, ConditionalTest<Annotation>> conditionTests = new HashMap<>();

    public void registerBeans(final List<BeanDefinition<?>> beanDefinitions) {
        beanDefinitions.stream()
                .filter(beanDefinition -> !this.hasConditions(beanDefinition))
                .forEach(this::doRegister);

        beanDefinitions.stream()
                .filter(this::hasConditions)
                .filter(this::testConditions)
                .forEach(this::doRegister);
    }

    private boolean hasConditions(final BeanDefinition<?> beanDefinition) {
        return beanDefinition.getAnnotations().values().stream()
                .anyMatch(annotation -> isCondition(annotation.annotationType()));
    }

    private boolean isCondition(final Class<? extends Annotation> annotation) {
        return annotation.getAnnotation(ConstructorProperties.class) != null;
    }

    private boolean testConditions(final BeanDefinition<?> beanDefinition) {
        var result = true;
        final var annotationIterator = beanDefinition.getAnnotations().values().iterator();

        while (result && annotationIterator.hasNext()) {
            final var annotation = annotationIterator.next();
            final var annotationType = annotation.annotationType();

            if (isCondition(annotationType)) {
                final var test = conditionTests.get(annotationType);
                if (test != null) {
                    if (!test.test(annotation, beanDefinition)) {
                        result = false;
                    }
                }
            }
        }

        return result;
    }

    private void doRegister(final BeanDefinition<?> beanDefinition) {
        final var sm = createScopeManager(beanDefinition);
        final var list = beansMap.computeIfAbsent(sm.getBeanType(), key -> new ArrayList<>());
        list.add(sm);
    }

    private ScopeManager<?> createScopeManager(final BeanDefinition<?> beanDefinition) {
        if (StandardScopes.PROTOTYPE.equals(beanDefinition.getScope())) {
            return new PrototypeScopeManager<>(beanDefinition);
        } else {
            return new LazySingletonScopeManager<>(beanDefinition);
        }
    }

    public <T> T createBean(final BeanDefinition<T> beanDefinition) {
        final var arguments = resolveArguments(beanDefinition.getBeanMethod());
        try {
            final var instance = beanDefinition.getAutoConfigInstance();
            final var method = beanDefinition.getBeanMethod();
            return (T) method.invoke(instance, arguments);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    public <T> void registerBean(final Class<?> beanClass,
                                 final T bean) {
        final var list = beansMap.computeIfAbsent(beanClass, key -> new ArrayList<>());
        list.add(new SingletonScopeManager<>(bean));
    }

    private Object[] resolveArguments(final Executable executable) {
        final Object[] arguments;

        if (executable.getParameterCount() == 0) {
            arguments = new Object[0];
        } else {
            return Arrays.stream(executable.getParameterTypes())
                    .map(this::getBeanOfType)
                    .toArray();
        }

        return arguments;
    }

    private <T> T getBeanOfType(final Class<T> beanType) {
        return resolveBean(beanType);
    }

    public <T> Set<T> getBeansOfType(final Class<T> beanType) {
        return resolveBeans(beanType);
    }

    private <T> T resolveBean(final Class<T> beanType) {
        final var resolvedBeans = resolveBeans(beanType);

        if (resolvedBeans.isEmpty()) {
            throw new DIException(String.format("Failed to resolve bean of %s", beanType.getName()));
        } else if (resolvedBeans.size() > 1) {
            throw new DIException(
                    String.format(
                            "Failed to resolve unique bean of %s. Found %s beans",
                            beanType.getName(),
                            resolvedBeans.size()
                    )
            );
        } else {
            return resolvedBeans.iterator().next();
        }
    }

    private <T> Set<T> resolveBeans(final Class<T> beanType) {
        if (beanType == null) {
            return Set.of();
        }

        final var list = beansMap.get(beanType);

        final Set<ScopeManager<?>> resolvedBeans = list != null
                ? new HashSet<>(list)
                : new HashSet<>();

        beansMap.keySet().forEach(beanClass -> {
            if (beanType.isAssignableFrom(beanClass)) {
                resolvedBeans.addAll(beansMap.get(beanClass));
            }
        });

        return resolvedBeans.stream()
                .map(sm -> beanType.cast(sm.get(this)))
                .collect(Collectors.toSet());
    }
}
