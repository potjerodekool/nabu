package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.CVariable;
import io.github.potjerodekool.nabu.compiler.tree.expression.BinaryExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.CIdent;
import io.github.potjerodekool.nabu.compiler.tree.expression.CFieldAccessExpression;

import java.util.ArrayList;
import java.util.List;

class UsedVarsCollector extends AbstractTreeVisitor<Object, TranslateContext> {

    private final List<CIdent> usedIdentifiers = new ArrayList<>();

    List<CIdent> getUsedIdentifiers() {
        return usedIdentifiers;
    }

    @Override
    public Object visitFieldAccessExpression(final CFieldAccessExpression fieldAccessExpression, final TranslateContext context) {
        fieldAccessExpression.getTarget().accept(this, context);
        return null;
    }

    @Override
    public Object visitIdentifier(final CIdent ident, final TranslateContext context) {
        final var index = context.frame.indexOf(ident.getName());

        if (index > -1) {
            usedIdentifiers.add(ident);
        }

        return null;
    }

    @Override
    public Object visitVariable(final CVariable variable, final TranslateContext context) {
        return null;
    }

    @Override
    public Object visitBinaryExpression(final BinaryExpression binaryExpression, final TranslateContext param) {
        return super.visitBinaryExpression(binaryExpression, param);
    }
}
