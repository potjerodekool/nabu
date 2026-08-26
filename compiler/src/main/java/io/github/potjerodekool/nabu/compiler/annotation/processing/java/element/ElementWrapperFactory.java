package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;

import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;

public final class ElementWrapperFactory {

    private static final Logger LOGGER = Logger.getLogger(ElementWrapperFactory.class.getName());

    private ElementWrapperFactory() {
    }

    public static Element wrap(final io.github.potjerodekool.nabu.lang.model.element.Element original) {
        return switch (original) {
            case null -> null;
            case TypeElement typeElement -> new JTypeElement(typeElement);
            case PackageSymbol packageSymbol -> new JPackageElement(packageSymbol);
            case VariableElement variableElement -> new JVariableElement(variableElement);
            case ExecutableElement executableElement -> new JExecutableElement(executableElement);
            case TypeParameterElement typeParameterElement -> new JTypeParameterElement(typeParameterElement);
            case ModuleElement moduleElement -> new JModuleElement(moduleElement);
            default -> {
                LOGGER.log(LogLevel.ERROR, "can't wrap " + original.getClass().getName());
                yield null;
            }
        };
    }

    public static AnnotationValue wrap(final io.github.potjerodekool.nabu.lang.model.element.AnnotationValue original) {
        return switch (original) {
            case EnumAttribute enumAttribute -> new JEnumAttribute(enumAttribute);
            case ConstantAttribute constantAttribute -> new JConstantAttribute(constantAttribute);
            case ArrayAttribute arrayAttribute -> new JArrayAttribute(arrayAttribute);
            case CompoundAttribute compoundAttribute -> new JCompoundAttribute(compoundAttribute);
            case ClassAttribute classAttribute -> new JClassAttribute(classAttribute);
            case null -> null;
            default -> {
                final var name = original.getClass().getName();
                LOGGER.log(LogLevel.ERROR, "can't wrap " + name);
                yield null;
            }
        };
    }

    public static io.github.potjerodekool.nabu.lang.model.element.Element unwrap(final Element element) {
        return ((JElement<?>) element).getOriginal();
    }

    public static io.github.potjerodekool.nabu.lang.model.element.Element toNabuElement(final Element element) {
        return switch (element) {
            case javax.lang.model.element.ExecutableElement executableElement -> new MethodSymbolBuilderImpl()
                    .kind(ElementKind.METHOD)
                    .simpleName(executableElement.getSimpleName().toString())
                    .build();
            default -> throw new UnsupportedOperationException("Cannot convert " + element.getKind() + " to nabu element");
        };
    }
}
