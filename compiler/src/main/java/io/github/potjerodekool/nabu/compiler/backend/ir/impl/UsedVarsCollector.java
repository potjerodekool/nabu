package io.github.potjerodekool.nabu.compiler.backend.ir.impl;

import io.github.potjerodekool.nabu.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;

import java.util.ArrayList;
import java.util.List;

class UsedVarsCollector extends AbstractTreeVisitor<Object, TranslateContext> {

    private final List<IdentifierTree> usedIdentifiers = new ArrayList<>();

    List<IdentifierTree> getUsedIdentifiers() {
        return usedIdentifiers;
    }

    @Override
    public Object visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression, final TranslateContext context) {
        acceptTree(fieldAccessExpression.getSelected(), context);
        return null;
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier, final TranslateContext context) {
        final var index = context.frame.indexOf(identifier.getName());

        if (index > -1) {
            usedIdentifiers.add(identifier);
        }

        return null;
    }

}
