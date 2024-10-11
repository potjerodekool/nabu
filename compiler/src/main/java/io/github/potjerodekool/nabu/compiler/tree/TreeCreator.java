package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.tree.expression.CExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.CIdent;
import io.github.potjerodekool.nabu.compiler.tree.expression.CTypeApply;
import io.github.potjerodekool.nabu.compiler.tree.expression.MethodInvocation;
import io.github.potjerodekool.nabu.compiler.type.ClassType;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableClassType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public final class TreeCreator {

    private TreeCreator() {
    }

    public static MethodInvocation methodInvocation(final CExpression target,
                                                    final CIdent name,
                                                    final CExpression... arguments) {
        final var methodInvocation = new MethodInvocation();
        methodInvocation.target(target);
        methodInvocation.name(name);
        methodInvocation.arguments(arguments);
        return methodInvocation;
    }

    public static CIdent createIdentifier(final String name) {
        return new CIdent(name);
    }

    public static CExpression createTypeTree(final TypeMirror typeMirror) {
        if (typeMirror instanceof ClassType classType) {
            final var clazz = (ClassSymbol) classType.asElement();
            final var ident = new CIdent(clazz.getQualifiedName());
            ident.setType(typeMirror);

            if (classType.getParameterTypes() == null) {
                return ident;
            } else {
                final var paramTypes = classType.getParameterTypes().stream()
                        .map(TreeCreator::createTypeTree)
                        .toList();
                final var typeApply = new CTypeApply(ident, paramTypes);
                typeApply.setType(typeMirror);
                return typeApply;
            }
        } else {
            throw new TodoException();
        }
    }
}
