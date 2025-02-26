package io.github.potjerodekool.dependencyinjection.bean;

import org.objectweb.asm.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AutoConfigReader extends ClassVisitor {

    private final Object autoConfigInstance;
    private final List<BeanDefinition<?>> beanDefinitions = new ArrayList<>();

    public AutoConfigReader(final Object autoConfigInstance) {
        super(Opcodes.ASM9);
        this.autoConfigInstance = autoConfigInstance;
    }

    Object getAutoConfigInstance() {
        return autoConfigInstance;
    }

    public List<BeanDefinition<?>> getBeanDefinitions() {
        return beanDefinitions;
    }

    public MethodVisitor visitMethod(final int access,
                                     final String name,
                                     final String descriptor,
                                     final String signature,
                                     final String[] exceptions) {
        return new AutoConfigMethodReader(api, name ,descriptor, this);
    }

    void addBeanDefinition(final BeanDefinition<?> beanDefinition) {
        this.beanDefinitions.add(beanDefinition);
    }
}

class AutoConfigMethodReader extends MethodVisitor {

    private final String methodName;
    private final String descriptor;
    private final AutoConfigReader autoConfigReader;

    private final Map<Class<?>, Annotation> annotationMap = new HashMap<>();

    public AutoConfigMethodReader(final int api,
                                  final String methodName,
                                  final String descriptor,
                                  final AutoConfigReader autoConfigReader) {
        super(api);
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.autoConfigReader = autoConfigReader;
    }

    @Override
    public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
        final var classname = descriptorToClassName(descriptor);
        return new AutoConfigAnnotationReader(api, classname, this);
    }

    private String descriptorToClassName(final String descriptor) {
        var className = descriptor.substring(1);
        className = className.substring(0, className.length() - 1);
        return className.replace('/', '.');
    }

    void addAnnotation(final Class<?> annotationClass, final Annotation annotation) {
        this.annotationMap.put(annotationClass, annotation);
    }

    @Override
    public void visitEnd() {
        if (annotationMap.containsKey(Bean.class)) {
            final var bean = (Bean) annotationMap.get(Bean.class);
            final var scope = bean.scope();

            try {
                final var returnType = getClass().getClassLoader().loadClass(
                        Type.getMethodType(
                                this.descriptor
                        ).getReturnType().getClassName()
                );

                final var beanDefinition = new BeanDefinition<>(
                        autoConfigReader.getAutoConfigInstance(),
                        methodName,
                        returnType,
                        this.annotationMap,
                        scope
                );
                autoConfigReader.addBeanDefinition(beanDefinition);
            } catch (final Exception ignored) {
                //Ignore
            }
        }
    }
}

class AutoConfigAnnotationReader extends AnnotationVisitor {

    private final String className;
    private final AutoConfigMethodReader methodReader;
    private final Map<String, Object> attributes = new HashMap<>();

    AutoConfigAnnotationReader(final int api,
                               final String className,
                               final AutoConfigMethodReader methodReader) {
        super(api);
        this.className = className;
        this.methodReader = methodReader;
    }

    @Override
    public void visit(final String name, final Object value) {
        if (value instanceof Type) {
            try {
                final var clazz = getClass().getClassLoader().loadClass(
                        ((Type) value).getClassName()
                );
                attributes.put(name, clazz);
            } catch (final ClassNotFoundException e) {
                //Ignore
            }
        } else {
            attributes.put(name, value);
        }
    }

    @Override
    public void visitEnd() {
        final var classLoader = getClass().getClassLoader();
        final Class<?> annotationClass;

        try {
            annotationClass = classLoader.loadClass(className);
        } catch (final ClassNotFoundException e) {
            return;
        }

        final var annotation = (Annotation) Proxy.newProxyInstance(
                classLoader,
                new Class[]{annotationClass},
                new AnnotationInvocationHandler(
                        annotationClass,
                        this.attributes
                )
        );

        methodReader.addAnnotation(annotationClass, annotation);
    }
}