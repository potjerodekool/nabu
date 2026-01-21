package io.github.potjerodekool.nabu.compiler.lang.support.java.lombok;

import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

public abstract class AbstractAccessorAnnotationHandler extends AbstractAnnotationHandler {

    protected String upperFirst(final String value) {
        final var first = Character.toUpperCase(value.charAt(0));

        if (value.length() == 1) {
            return Character.toString(first);
        } else {
            return first + value.substring(1);
        }
    }

    protected long accessLevel(final VariableDeclaratorTree field,
                               final ClassDeclaration classDeclaration) {
        final var annotationName = getAnnotationName();

        var annotationOptional = findAnnotation(annotationName, field);

        if (annotationOptional.isEmpty()) {
            annotationOptional = findAnnotation(annotationName, classDeclaration);
        }

        if (annotationOptional.isEmpty()) {
            return 0L;
        }

        final var annotation = annotationOptional.get();
        final var accessLevel = (FieldAccessExpressionTree) annotation.getArgumentValue("value");
        final var accessLevelName = accessLevel.getField().getName();

        return switch (accessLevelName) {
            case "PROTECTED" -> Flags.PROTECTED;
            case "PACKAGE" -> 0;
            case "PRIVATE" -> Flags.PRIVATE;
            default -> Flags.PUBLIC;
        };
    }
}
