package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
import io.github.potjerodekool.nabu.tools.TodoException;

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
                throw new TodoException(original.getClass().getName());
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
                throw new TodoException();
            }
        };
    }

    public static io.github.potjerodekool.nabu.lang.model.element.Element unwrap(final Element element) {
        return ((JElement<?>) element).getOriginal();
    }
}
