package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.MethodBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.*;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.CompoundAttribute;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Map;

public class AsmAnnotationBuilder extends AbstractAsmAnnotationBuilder {

    private final CompoundAttribute annotation;

    public static AsmAnnotationBuilder createBuilder(final int api,
                                                     final String descriptor,
                                                     final boolean visible,
                                                     final Symbol annotationTarget,
                                                     final ClassElementLoader loader) {
        final var annotation = createAnnotation(descriptor, loader);
        annotationTarget.addAnnotationMirror(annotation);
        return new AsmAnnotationBuilder(
                api,
                visible,
                loader,
                annotation
        );
    }

    protected AsmAnnotationBuilder(final int api,
                                   final boolean visible,
                                   final ClassElementLoader loader,
                                   final CompoundAttribute annotation) {
        super(api, visible, annotation, loader);
        this.annotation = annotation;
    }

    @Override
    protected void addAttribute(final String name,
                                final Attribute attribute) {
        final var method = new MethodBuilder()
                .kind(ElementKind.METHOD)
                .name(name)
                .build();
        this.annotation.addValue(method, attribute);
    }

}

abstract class AbstractAsmAnnotationBuilder extends AnnotationVisitor {

    protected final boolean visible;
    private final Attribute attribute;
    protected final ClassElementLoader loader;

    protected AbstractAsmAnnotationBuilder(final int api,
                                           final boolean visible,
                                           final Attribute attribute,
                                           final ClassElementLoader loader) {
        super(api);
        this.visible = visible;
        this.attribute = attribute;
        this.loader = loader;
    }

    protected static CompoundAttribute createAnnotation(final String descriptor,
                                                        final ClassElementLoader loader) {
        final var annotationType = loadTypeFromDescriptor(descriptor, loader);
        return new CompoundAttribute(annotationType, Map.of());
    }

    private DeclaredType loadTypeFromDescriptor(final String descriptor) {
        return loadTypeFromDescriptor(descriptor, loader);
    }

    private static DeclaredType loadTypeFromDescriptor(final String descriptor,
                                                       final ClassElementLoader loader) {
        final var asmType = Type.getType(descriptor);
        return (DeclaredType) loader.loadClass(asmType.getClassName()).asType();
    }

    protected abstract void addAttribute(final String name,
                                         final Attribute attribute);

    @Override
    public void visit(final String name, final Object value) {
        addAttribute(name, AnnotationBuilder.createConstantValue(value));
    }

    @Override
    public void visitEnum(final String name, final String descriptor, final String value) {
        final var enumType = loadTypeFromDescriptor(descriptor);
        addAttribute(name, new EnumAttributeProxy(enumType, value));
    }

    @Override
    public AnnotationVisitor visitArray(final String name) {
        final var arrayAttribute = new ArrayAttributeProxy(List.of());
        addAttribute(name, arrayAttribute);

        return new AsmArrayAnnotationBuilder(
                api,
                visible,
                loader,
                arrayAttribute,
                attribute
        );
    }

    @Override
    public AnnotationVisitor visitAnnotation(final String name,
                                             final String descriptor) {
        final var annotation = createAnnotation(descriptor, loader);
        addAttribute(name, annotation);

        return createBuilder(
                api,
                visible,
                loader,
                annotation
        );
    }

    AsmAnnotationBuilder createBuilder(final int api,
                                       final boolean visible,
                                       final ClassElementLoader loader,
                                       final CompoundAttribute attribute) {
        return new AsmAnnotationBuilder(
                api,
                visible,
                loader,
                attribute
        );
    }

}

class AsmArrayAnnotationBuilder extends AbstractAsmAnnotationBuilder {

    private final ArrayAttributeProxy arrayAttribute;

    public AsmArrayAnnotationBuilder(final int api,
                                     final boolean visible,
                                     final ClassElementLoader loader,
                                     final ArrayAttributeProxy arrayAttribute,
                                     final Attribute attribute) {
        super(api, visible, attribute, loader);
        this.arrayAttribute = arrayAttribute;
    }

    @Override
    protected void addAttribute(final String name,
                                final Attribute attribute) {
        this.arrayAttribute.addValue(attribute);
    }
}
