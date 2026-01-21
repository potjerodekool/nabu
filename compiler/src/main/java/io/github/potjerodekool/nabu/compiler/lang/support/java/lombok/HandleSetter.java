package io.github.potjerodekool.nabu.compiler.lang.support.java.lombok;

import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.TreeFilter;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.PrimitiveTypeTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Adds getters to the AST when @Setter is used.
 */
public class HandleSetter extends AbstractAccessorAnnotationHandler {

    private final Types types;

    public HandleSetter(final Types types) {
        this.types = types;
    }

    @Override
    public String getAnnotationName() {
        return "lombok.Setter";
    }

    @Override
    public void handle(final ClassDeclaration classDeclaration) {
        final var fields = TreeFilter.fieldsIn(classDeclaration.getEnclosedElements()).stream()
                .filter(field -> !field.hasFlag(Flags.STATIC))
                .toList();

        fields.forEach(field -> {
            final var setterOptional = findSetter(field.getName().getName(), field.getType(), classDeclaration);
            if (setterOptional.isEmpty()) {
                final var accessLevel = accessLevel(field, classDeclaration);
                addSetter(field, accessLevel, classDeclaration);
            }
        });
    }

    @Override
    public void handle(final VariableDeclaratorTree field,
                       final ClassDeclaration classDeclaration) {
        final var setterOptional = findSetter(field.getName().getName(), field.getType(), classDeclaration);
        if (setterOptional.isEmpty()) {
            final var accessLevel = accessLevel(field, classDeclaration);
            addSetter(field, accessLevel, classDeclaration);
        }
    }

    private String createSetterName(final String fieldName) {
        return "set" + upperFirst(fieldName);
    }

    private Optional<Function> findSetter(final String fieldName,
                                          final TypeMirror fieldType,
                                          final ClassDeclaration classDeclaration) {
        final var setterName = createSetterName(fieldName);

        return TreeFilter.methodsIn(classDeclaration.getEnclosedElements()).stream()
                .filter(method -> setterName.equals(method.getSimpleName()))
                .filter(method -> method.getParameters().size() == 1)
                .filter(method -> types.isSameType(method.getParameters().getFirst().getVariableType().getType(), fieldType))
                .findFirst();
    }

    private void addSetter(final VariableDeclaratorTree field,
                           final long accessLevel,
                           final ClassDeclaration classDeclaration) {
        final var paramType = (ExpressionTree) field.getVariableType().builder()
                .build();
        final var returnType = types.getNoType(TypeKind.VOID);
        final var returnTypeTree = TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.VOID, 0, 0);
        returnTypeTree.setType(returnType);

        final var parameter = TreeMaker.variableDeclarator(
                Kind.PARAMETER,
                new Modifiers(),
                paramType,
                IdentifierTree.create(field.getName().getName()),
                null,
                null,
                0,
                0
        );

        final var setterName = createSetterName(field.getName().getName());
        final var setter = TreeMaker.function(
                setterName,
                Kind.METHOD,
                new Modifiers(accessLevel),
                Collections.emptyList(),
                null,
                List.of(parameter),
                returnTypeTree,
                Collections.emptyList(),
                TreeMaker.blockStatement(List.of(), 0, 0),
                null,
                0,
                0
        );

        classDeclaration.enclosedElement(setter);
    }
}
