package io.github.potjerodekool.nabu.compiler.lang.support.java.lombok;

import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.TreeFilter;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Adds getters to the AST when @Getter is used.
 */
public class HandleGetter extends AbstractAccessorAnnotationHandler {

    private final Types types;

    public HandleGetter(final Types types) {
        this.types = types;
    }

    @Override
    public String getAnnotationName() {
        return "lombok.Getter";
    }

    @Override
    public void handle(final ClassDeclaration classDeclaration) {
        final var fields = TreeFilter.fieldsIn(classDeclaration.getEnclosedElements()).stream()
                .filter(field -> !field.hasFlag(Flags.STATIC))
                .toList();

        fields.forEach(field -> {
            final var getterOptional = findGetter(field.getName().getName(), field.getType(), classDeclaration);
            if (getterOptional.isEmpty()) {
                final var accessLevel = accessLevel(field, classDeclaration);
                addGetter(field, accessLevel, classDeclaration);
            }
        });
    }

    @Override
    public void handle(final VariableDeclaratorTree field,
                       final ClassDeclaration classDeclaration) {
        final var getterOptional = findGetter(field.getName().getName(), field.getType(), classDeclaration);
        if (getterOptional.isEmpty()) {
            final var accessLevel = accessLevel(field, classDeclaration);
            addGetter(field, accessLevel, classDeclaration);
        }
    }

    private String createGetterName(final String fieldName) {
        return "get" + upperFirst(fieldName);
    }

    private Optional<Function> findGetter(final String fieldName,
                                          final TypeMirror fieldType,
                                          final ClassDeclaration classDeclaration) {
        final var getterName = createGetterName(fieldName);

        return TreeFilter.methodsIn(classDeclaration.getEnclosedElements()).stream()
                .filter(method -> getterName.equals(method.getSimpleName()))
                .filter(method -> method.getParameters().isEmpty())
                .filter(method -> types.isSameType(method.getReturnType().getType(), fieldType))
                .findFirst();
    }

    private void addGetter(final VariableDeclaratorTree field,
                           final long accessLevel,
                           final ClassDeclaration classDeclaration) {
        final var returnType = (ExpressionTree) field.getVariableType().builder()
                .build();

        final var getterName = createGetterName(field.getName().getName());
        final var getter = TreeMaker.function(
                getterName,
                Kind.METHOD,
                new Modifiers(accessLevel),
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                returnType,
                Collections.emptyList(),
                TreeMaker.blockStatement(List.of(), 0, 0),
                null,
                0,
                0
        );

        classDeclaration.enclosedElement(getter);
    }
}
