package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ElementBuildersImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.lang.model.element.CArrayAttributeProxy;
import io.github.potjerodekool.nabu.lang.model.element.CCompoundAttribute;
import io.github.potjerodekool.nabu.lang.model.element.CEnumAttributeProxy;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.lang.model.element.Attribute;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.type.DeclaredType;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Map;

public class AsmAnnotationBuilder extends AbstractAsmAnnotationBuilder {

    private final CCompoundAttribute annotation;

    public static AsmAnnotationBuilder createBuilder(final int api,
                                                     final String descriptor,
                                                     final boolean visible,
                                                     final Symbol annotationTarget,
                                                     final ClassElementLoader loader,
                                                     final ModuleSymbol moduleSymbol) {
        final var annotation = createAnnotation(descriptor, loader, moduleSymbol);
        annotationTarget.addAnnotationMirror(annotation);
        return new AsmAnnotationBuilder(
                api,
                visible,
                loader,
                annotation,
                moduleSymbol

        );
    }

    public AsmAnnotationBuilder(final int api,
                                final boolean visible,
                                final ClassElementLoader loader,
                                final CCompoundAttribute annotation,
                                final ModuleSymbol moduleSymbol) {
        super(api, visible, annotation, loader, moduleSymbol);
        this.annotation = annotation;
    }

    @Override
    protected void addAttribute(final String name,
                                final Attribute attribute) {
        final var returnType = attribute.getType();

        final var method = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .simpleName(name)
                .returnType(returnType)
                .build();
        this.annotation.addValue(method, attribute);
    }

}

abstract class AbstractAsmAnnotationBuilder extends AnnotationVisitor {

    protected final boolean visible;
    private final Attribute attribute;
    protected final ClassElementLoader loader;
    private final ModuleSymbol moduleSymbol;

    protected AbstractAsmAnnotationBuilder(final int api,
                                           final boolean visible,
                                           final Attribute attribute,
                                           final ClassElementLoader loader,
                                           final ModuleSymbol moduleSymbol) {
        super(api);
        this.visible = visible;
        this.attribute = attribute;
        this.loader = loader;
        this.moduleSymbol = moduleSymbol;
    }

    protected static CCompoundAttribute createAnnotation(final String descriptor,
                                                         final ClassElementLoader loader,
                                                         final ModuleSymbol moduleSymbol) {
        final var annotationType = loadTypeFromDescriptor(descriptor, loader, moduleSymbol);
        return new CCompoundAttribute(annotationType, Map.of());
    }

    private DeclaredType loadTypeFromDescriptor(final String descriptor) {
        return loadTypeFromDescriptor(descriptor, loader, moduleSymbol);
    }

    private static DeclaredType loadTypeFromDescriptor(final String descriptor,
                                                       final ClassElementLoader loader,
                                                       final ModuleSymbol moduleSymbol) {
        final var asmType = Type.getType(descriptor);
        var clazz = loader.loadClass(moduleSymbol, asmType.getClassName());

        if (clazz == null) {
            clazz = loader.loadClass(moduleSymbol, asmType.getClassName());
        }

        return (DeclaredType) clazz.asType();
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

        final var variableElement = ElementBuildersImpl.getInstance().variableElementBuilder()
                .simpleName(value)
                .type(enumType)
                .build();

        addAttribute(name, new CEnumAttributeProxy(variableElement));
    }

    @Override
    public AnnotationVisitor visitArray(final String name) {
        final var arrayAttribute = new CArrayAttributeProxy(List.of());
        addAttribute(name, arrayAttribute);

        return new AsmArrayAnnotationBuilder(
                api,
                visible,
                loader,
                arrayAttribute,
                attribute,
                moduleSymbol
        );
    }

    @Override
    public AnnotationVisitor visitAnnotation(final String name,
                                             final String descriptor) {
        final var annotation = createAnnotation(descriptor, loader, moduleSymbol);
        addAttribute(name, annotation);

        return createBuilder(
                api,
                visible,
                loader,
                annotation,
                moduleSymbol
        );
    }

    AsmAnnotationBuilder createBuilder(final int api,
                                       final boolean visible,
                                       final ClassElementLoader loader,
                                       final CCompoundAttribute attribute,
                                       final ModuleSymbol moduleSymbol) {
        return new AsmAnnotationBuilder(
                api,
                visible,
                loader,
                attribute,
                moduleSymbol
        );
    }

}

class AsmArrayAnnotationBuilder extends AbstractAsmAnnotationBuilder {

    private final CArrayAttributeProxy arrayAttribute;

    public AsmArrayAnnotationBuilder(final int api,
                                     final boolean visible,
                                     final ClassElementLoader loader,
                                     final CArrayAttributeProxy arrayAttribute,
                                     final Attribute attribute,
                                     final ModuleSymbol moduleSymbol) {
        super(api, visible, attribute, loader, moduleSymbol);
        this.arrayAttribute = arrayAttribute;
    }

    @Override
    protected void addAttribute(final String name,
                                final Attribute attribute) {
        this.arrayAttribute.addValue(attribute);
    }
}
