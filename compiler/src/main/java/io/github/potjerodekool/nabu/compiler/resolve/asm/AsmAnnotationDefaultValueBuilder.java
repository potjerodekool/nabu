package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.lang.model.element.CArrayAttributeProxy;
import io.github.potjerodekool.nabu.lang.model.element.CCompoundAttribute;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.lang.model.element.Attribute;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.type.DeclaredType;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Map;

public class AsmAnnotationDefaultValueBuilder extends AbstractAsmAnnotationDefaultValueBuilder {

    private final MethodSymbol methodSymbol;

    public AsmAnnotationDefaultValueBuilder(final int api,
                                            final ClassElementLoader loader,
                                            final MethodSymbol method,
                                            final ModuleSymbol moduleSymbol) {
        super(api, loader, moduleSymbol);
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
    private final ModuleSymbol moduleSymbol;

    protected AbstractAsmAnnotationDefaultValueBuilder(final int api,
                                                       final ClassElementLoader loader,
                                                       final ModuleSymbol moduleSymbol) {
        super(api);
        this.loader = loader;
        this.moduleSymbol = moduleSymbol;
    }

    private DeclaredType loadTypeFromDescriptor(final String descriptor) {
        final var asmType = Type.getType(descriptor);
        return (DeclaredType) loader.loadClass(moduleSymbol, asmType.getClassName()).asType();
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
        final var enumConstant = new VariableSymbolBuilderImpl()
                .kind(ElementKind.ENUM_CONSTANT)
                .simpleName(value)
                .type(enumType)
                .build();

        final var enumAttribute = AnnotationBuilder.createEnumValue(enumType, enumConstant);
        addAttribute(name, enumAttribute);
    }

    @Override
    public AnnotationVisitor visitAnnotation(final String name,
                                             final String descriptor) {
        final var type = loadTypeFromDescriptor(descriptor);
        final var annotation = new CCompoundAttribute(type, Map.of());
        addAttribute(name, annotation);

        return new AsmAnnotationDefaultValueAnnotationBuilder(
                api,
                loader,
                annotation,
                moduleSymbol
        );
    }

    @Override
    public AnnotationVisitor visitArray(final String name) {
        final var attribute = new CArrayAttributeProxy(List.of());

        addAttribute(name, attribute);
        return new AsmAnnotationDefaultValueArrayBuilder(
                api,
                loader,
                attribute,
                moduleSymbol
        );
    }

}

class AsmAnnotationDefaultValueArrayBuilder extends AbstractAsmAnnotationDefaultValueBuilder {

    private final CArrayAttributeProxy arrayAttribute;

    protected AsmAnnotationDefaultValueArrayBuilder(final int api,
                                                    final ClassElementLoader loader,
                                                    final CArrayAttributeProxy attribute,
                                                    final ModuleSymbol moduleSymbol) {
        super(api, loader, moduleSymbol);
        this.arrayAttribute = attribute;
    }

    @Override
    protected void addAttribute(final String name,
                                final Attribute attribute) {
        this.arrayAttribute.addValue(attribute);
    }
}

class AsmAnnotationDefaultValueAnnotationBuilder extends AbstractAsmAnnotationDefaultValueBuilder {

    private final CCompoundAttribute annotation;

    protected AsmAnnotationDefaultValueAnnotationBuilder(final int api,
                                                         final ClassElementLoader loader,
                                                         final CCompoundAttribute annotation,
                                                         final ModuleSymbol moduleSymbol) {
        super(api, loader, moduleSymbol);
        this.annotation = annotation;
    }

    @Override
    protected void addAttribute(final String name, final Attribute attribute) {
        this.annotation.addValue(null, attribute);
    }
}