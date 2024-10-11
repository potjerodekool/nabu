package io.github.potjerodekool.nabu.compiler.enhance;

import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.CElement;
import io.github.potjerodekool.nabu.compiler.tree.element.CFunction;
import io.github.potjerodekool.nabu.compiler.tree.expression.CIdent;
import io.github.potjerodekool.nabu.compiler.tree.expression.CNoTypeExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.MethodInvocation;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.ReturnStatement;

public class Enhancer extends AbstractTreeVisitor<Object, Object> {

    @Override
    public Object visitClass(final CClassDeclaration classDeclaration, final Object param) {
        final var name = classDeclaration.getSimpleName();

        final var constructor = new CFunction()
                .simpleName(name)
                .kind(CElement.Kind.CONSTRUCTOR)
                .returnType(new CNoTypeExpression());

        final var body = new BlockStatement();

        final var superCall = new MethodInvocation()
                .target(new CIdent("this"))
                .name(new CIdent("super"));
        superCall.setLineNumber(classDeclaration.getLineNumber());
        superCall.setColumnNumber(classDeclaration.getColumnNumber());

        body.statement(superCall);
        body.statement(
                new ReturnStatement()
        );

        constructor.body(body);

        classDeclaration.enclosedElement(constructor, 0);

        return super.visitClass(classDeclaration, param);
    }
}
