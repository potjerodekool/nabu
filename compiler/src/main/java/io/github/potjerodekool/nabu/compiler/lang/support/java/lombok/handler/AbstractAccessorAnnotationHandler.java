package io.github.potjerodekool.nabu.compiler.lang.support.java.lombok.handler;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.ClassAttribute;
import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.Map;
import java.util.Optional;

public abstract class AbstractAccessorAnnotationHandler extends AbstractAnnotationHandler {

    @Override
    public void handle(final ClassSymbol classSymbol) {
        final var fields = ElementFilter.fieldsIn(classSymbol.getEnclosedElements()).stream()
                .filter(field -> !field.hasFlag(Flags.STATIC))
                .toList();

        fields.forEach(field -> {
            final var accessorMethodOptional = findAccessorMethod(field.getSimpleName(), field.asType(), classSymbol);
            if (accessorMethodOptional.isEmpty()) {
                final var accessLevel = accessLevel(field, classSymbol);
                addAccessorMethod(field, accessLevel, classSymbol);
            }
        });
    }

    @Override
    public void handle(final VariableSymbol field,
                       final ClassSymbol classSymbol) {
        final var getterOptional = findAccessorMethod(field.getSimpleName(), field.asType(), classSymbol);
        if (getterOptional.isEmpty()) {
            final var accessLevel = accessLevel(field, classSymbol);
            addAccessorMethod(field, accessLevel, classSymbol);
        }
    }

    protected abstract Optional<ExecutableElement> findAccessorMethod(final String fieldName,
                                                                      final TypeMirror fieldType,
                                                                      final ClassSymbol classDeclaration);

    protected abstract void addAccessorMethod(final VariableElement field,
                           final long accessLevel,
                           final ClassSymbol classDeclaration);


    protected String upperFirst(final String value) {
        final var first = Character.toUpperCase(value.charAt(0));

        if (value.length() == 1) {
            return Character.toString(first);
        } else {
            return first + value.substring(1);
        }
    }

    protected long accessLevel(final VariableElement field,
                               final ClassSymbol classDeclaration) {
        final var annotationName = getAnnotationName();

        var annotationOptional = findAnnotation(annotationName, field);

        if (annotationOptional.isEmpty()) {
            annotationOptional = findAnnotation(annotationName, classDeclaration);
        }

        if (annotationOptional.isEmpty()) {
            return 0L;
        }

        final var annotation = annotationOptional.get();
        final var values = annotation.getElementValues();
        final var valueOptional = values.entrySet().stream()
                .filter(it -> it.getKey().getSimpleName().equals("value"))
                .map(Map.Entry::getValue)
                .findFirst();

        if (valueOptional.isPresent()) {
            final var value = (ClassAttribute) valueOptional.get();
            final var type = (DeclaredType) value.getValue();
            final var accessLevelName = type.asElement().getSimpleName();

            return switch (accessLevelName) {
                case "PROTECTED" -> Flags.PROTECTED;
                case "PACKAGE" -> 0;
                case "PRIVATE" -> Flags.PRIVATE;
                default -> Flags.PUBLIC;
            };
        } else {
            return Flags.PUBLIC;
        }


    }
}
