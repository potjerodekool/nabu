package io.github.potjerodekool.nabu.compiler.ast.symbol;

import io.github.potjerodekool.nabu.compiler.ast.element.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

class AnnotationInvocationHandler implements InvocationHandler {

    private final Class<?> annotationClass;
    private final Map<String, Object> memberValues;
    private Method[] methods;

    public AnnotationInvocationHandler(final AnnotationMirror annotationMirror,
                                       final ClassLoader classLoader) {
        final var annotationType = annotationMirror.getAnnotationType();
        final var className = annotationType.asTypeElement().getQualifiedName();
        this.annotationClass = AnnotationUtils.loadClass(className, classLoader);

        final var methodMap = ElementFilter.methods(annotationType.asTypeElement()).stream()
                .collect(
                        Collectors.toMap(
                                Element::getSimpleName,
                                it -> it
                        )
                );

        //First fill with default values
        final var values = ElementFilter.methods(annotationType.asTypeElement()).stream()
                .filter(it -> Objects.nonNull(it.getDefaultValue()))
                .collect(
                        Collectors.toMap(
                                Element::getSimpleName,
                                ExecutableElement::getDefaultValue
                        )
                );

        //Fill with specified values.
        annotationMirror.getElementValues().entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .forEach(entry -> values.put(
                        entry.getKey().getSimpleName(),
                        entry.getValue()
                ));

        this.memberValues = generateRawValue(values, methodMap);
    }

    private Map<String, Object> generateRawValue(final Map<String, AnnotationValue> values, final Map<String, ExecutableElement> methodMap) {
        final var map = new HashMap<String, Object>(values.size());
        final var collector = new ValueCollector();

        values.forEach((k, v) -> {
            final var rawValue = v.accept(collector, methodMap.get(k));

            if (rawValue != null) {
                map.put(k, rawValue);
            }
        });

        return map;
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) {
        final var methodName = method.getName();

        if (args == null || args.length == 0) {
            if ("toString".equals(methodName)) {
                return generateToString();
            } else if ("hascode".equals(methodName)) {
                return generateHashCode();
            }
        } else if (args.length == 1
                && method.getParameterTypes()[0] == Object.class
                && "equals".equals(methodName)) {
            return equalsCheck(proxy, args[0]);
        }

        return memberValues.get(methodName);
    }

    private String generateToString() {
        return "@" +
                annotationClass.getName() +
                memberValues.entrySet().stream()
                        .map(entry -> entry.getKey() + " = " +
                                valueToString(entry.getValue()))
                        .collect(Collectors.joining(", ", "(", ")"));
    }

    private String valueToString(final Object value) {
        if (value instanceof Enum<?> enumValue) {
            final var className = enumValue.getDeclaringClass().getName();
            return className + "." + enumValue.name();
        } else if (value.getClass().isArray()) {
            final var array = (Object[]) value;
            return Arrays.stream(array)
                    .map(this::valueToString)
                    .collect(Collectors.joining(",", "[", "]"));
        } else if (value instanceof Annotation) {
            return value.toString();
        } else {
            return value.toString();
        }
    }

    private int generateHashCode() {
        return memberValues.entrySet().stream()
                .mapToInt(entry ->
                        (127 * entry.getKey().hashCode()) ^ hasCodeOf(entry.getValue()))
                .reduce(Integer::sum)
                .orElse(0);
    }

    private static int hasCodeOf(final Object value) {
        final var type = value.getClass();

        if (!type.isArray()) {
            return value.hashCode();
        } else if (type == byte[].class) {
            return Arrays.hashCode((byte[]) value);
        } else if (type == char[].class) {
            return Arrays.hashCode((char[]) value);
        } else if (type == double[].class) {
            return Arrays.hashCode((double[]) value);
        } else if (type == float[].class) {
            return Arrays.hashCode((float[]) value);
        } else if (type == int[].class) {
            return Arrays.hashCode((int[]) value);
        } else if (type == long[].class) {
            return Arrays.hashCode((long[]) value);
        } else if (type == short[].class) {
            return Arrays.hashCode((short[]) value);
        } else if (type == boolean[].class) {
            return Arrays.hashCode((boolean[]) value);
        } else {
            return Arrays.hashCode((Object[]) value);
        }
    }

    private boolean equalsCheck(final Object proxy,
                                final Object other) {
        if (other == proxy) {
            return true;
        } else if (!annotationClass.isInstance(other)) {
            return false;
        }

        return Arrays.stream(getMemberMethods())
                .filter(it -> !it.isSynthetic())
                .allMatch(method -> {
                    final var ourValue = memberValues.get(method.getName());
                    final var handler = getHandler(other);
                    final Object otherValue;

                    if (handler != null) {
                        otherValue = handler.memberValues.get(method.getName());
                    } else {
                        try {
                            otherValue = method.invoke(other);
                        } catch (final IllegalAccessException | InvocationTargetException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    return valueEquals(ourValue, otherValue);
                });
    }

    private boolean valueEquals(final Object value1,
                                final Object value2) {
        final var type = value1.getClass();

        if (!type.isArray()) {
            return Objects.equals(value1, value2);
        } else if (value1 instanceof Object[] array1
                && value2 instanceof Object[] array2) {
            return Arrays.equals(array1, array2);
        } else if (type == byte[].class) {
            return Arrays.equals((byte[]) value1, (byte[]) value2);
        } else if (type == char[].class) {
            return Arrays.equals((char[]) value1, (char[]) value2);
        } else if (type == short[].class) {
            return Arrays.equals((short[]) value1, (short[]) value2);
        } else if (type == int[].class) {
            return Arrays.equals((int[]) value1, (int[]) value2);
        } else if (type == boolean[].class) {
            return Arrays.equals((boolean[]) value1, (boolean[]) value2);
        } else if (type == float[].class) {
            return Arrays.equals((float[]) value1, (float[]) value2);
        } else if (type == double[].class) {
            return Arrays.equals((double[]) value1, (double[]) value2);
        } else if (type == long[].class) {
            return Arrays.equals((long[]) value1, (long[]) value2);
        } else {
            return false;
        }
    }

    private Method[] getMemberMethods() {
        if (methods == null) {
            methods = resolveMemberMethods();
        }
        return methods;
    }

    private Method[] resolveMemberMethods() {
        final var methods = annotationClass.getDeclaredMethods();
        AccessibleObject.setAccessible(methods, true);
        return methods;
    }

    private AnnotationInvocationHandler getHandler(final Object o) {
        if (Proxy.isProxyClass(o.getClass())) {
            final var handler = Proxy.getInvocationHandler(o);

            if (handler instanceof AnnotationInvocationHandler annotationInvocationHandler) {
                return annotationInvocationHandler;
            }
        }

        return null;
    }

}
