package io.github.potjerodekool.dependencyinjection.bean;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.StringJoiner;

public class AnnotationInvocationHandler implements InvocationHandler {

    private final Class<?> annotationClass;
    private final Map<String, Object> attributes;

    public AnnotationInvocationHandler(final Class<?> annotationClass,
                                       final Map<String, Object> attributes) {
        this.annotationClass = annotationClass;
        this.attributes = attributes;
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
        final var methodName = method.getName();

        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }

        return switch (methodName) {
            case "annotationType" -> annotationClass;
            case "equals" -> !(args == null || args.length == 0) && this == args[0];
            case "hashCode" -> this.hashCode();
            case "toString" -> this.toString();
            default -> {
                if (method.getReturnType() == Void.class || method.getReturnType() == Void.TYPE) {
                    yield null;
                } else if (method.getParameterCount() == 0) {
                    if (attributes.containsKey(methodName)) {
                        yield attributes.get(methodName);
                    } else {
                        yield getDefaultValue(methodName);
                    }
                } else {
                    yield null;
                }
            }
        };
    }

    private Object getDefaultValue(final String methodName) {
        try {
            final var method = annotationClass.getDeclaredMethod(methodName);
            return method.getDefaultValue();
        } catch (final NoSuchMethodException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        final var sb = new StringBuilder();
        sb.append("@");
        sb.append(annotationClass.getName());
        sb.append("(");

        final var attributeJoiner = new StringJoiner(",");

        attributes.forEach((key, value) -> attributeJoiner.add(quoteString(key) + "=" + quoteString(value)));

        sb.append(attributeJoiner);
        sb.append(")");

        return sb.toString();
    }

    private Object quoteString(final Object value) {
        return value instanceof String
                ? "\"" + value + "\""
                : value;
    }
}
