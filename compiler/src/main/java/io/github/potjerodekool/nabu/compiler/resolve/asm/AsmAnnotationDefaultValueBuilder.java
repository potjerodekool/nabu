package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.*;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.CompoundAttribute;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Map;

public class AsmAnnotationDefaultValueBuilder extends AbstractAsmAnnotationDefaultValueBuilder {

    private final MethodSymbol methodSymbol;

    public AsmAnnotationDefaultValueBuilder(final int api,
                                            final ClassElementLoader loader,
                                            final MethodSymbol method) {
        super(api, loader);
        this.methodSymbol = method;
    }

    @Override
    protected void addAttribute(final String name,
                                final Attribute attribute) {
        methodSymbol.setDefaultValue(attribute);
    }
}

abstract class AbstractAsmAnnotationDefaultValueBuilder extends AnnotationVisitor {

    protected final ClassElementLoader loader;

    protected AbstractAsmAnnotationDefaultValueBuilder(final int api,
                                                       final ClassElementLoader loader) {
        super(api);
        this.loader = loader;
    }

    private DeclaredType loadTypeFromDescriptor(final String descriptor) {
        final var asmType = Type.getType(descriptor);
        return (DeclaredType) loader.loadClass(asmType.getClassName()).asType();
    }

    protected abstract void addAttribute(final String name,
                                         final Attribute attribute);

    @Override
    public void visit(final String name,
                      final Object value) {
        addAttribute(name, AnnotationBuilder.createConstantValue(value));
    }

    @Override
    public void visitEnum(final String name,
                          final String descriptor,
                          final String value) {
        final var enumType = loadTypeFromDescriptor(descriptor);
        final var enumConstant = new VariableBuilder()
                .kind(ElementKind.ENUM_CONSTANT)
                .name(value)
                .type(enumType)
                .build();

        final var enumAttribute = AnnotationBuilder.createEnumValue(enumType, enumConstant);
        addAttribute(name, enumAttribute);
    }

    @Override
    public AnnotationVisitor visitAnnotation(final String name,
                                             final String descriptor) {
        final var type = loadTypeFromDescriptor(descriptor);
        final var annotation = new CompoundAttribute(type, Map.of());
        addAttribute(name, annotation);

        return new AsmAnnotationDefaultValueAnnotationBuilder(
                api,
                loader,
                annotation
        );
    }

    @Override
    public AnnotationVisitor visitArray(final String name) {
        final var attribute = new ArrayAttributeProxy(List.of());

        addAttribute(name, attribute);
        return new AsmAnnotationDefaultValueArrayBuilder(
                api,
                loader,
                attribute
        );
    }

}

class AsmAnnotationDefaultValueArrayBuilder extends AbstractAsmAnnotationDefaultValueBuilder {

    private final ArrayAttributeProxy arrayAttribute;

    protected AsmAnnotationDefaultValueArrayBuilder(final int api,
                                                    final ClassElementLoader loader,
                                                    final ArrayAttributeProxy attribute) {
        super(api, loader);
        this.arrayAttribute = attribute;
    }

    @Override
    protected void addAttribute(final String name,
                                final Attribute attribute) {
        this.arrayAttribute.addValue(attribute);
    }
}

class AsmAnnotationDefaultValueAnnotationBuilder extends AbstractAsmAnnotationDefaultValueBuilder {

    private final CompoundAttribute annotation;

    protected AsmAnnotationDefaultValueAnnotationBuilder(final int api,
                                                       final ClassElementLoader loader,
                                                       final CompoundAttribute annotation) {
        super(api, loader);
        this.annotation = annotation;
    }

    @Override
    protected void addAttribute(final String name, final Attribute attribute) {
        this.annotation.addValue(null, attribute);
    }
}