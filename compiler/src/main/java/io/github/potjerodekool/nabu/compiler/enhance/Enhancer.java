package io.github.potjerodekool.nabu.compiler.enhance;

import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.PrimitiveTypeTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.ReturnStatement;

public class Enhancer extends AbstractTreeVisitor<Object, Object> {

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration, final Object param) {
        if (!hasConstructor(classDeclaration)) {
            final var name = classDeclaration.getSimpleName();

            final var constructor = new Function(
                    -1,
                    -1
            )
                    .simpleName(name)
                    .kind(Element.Kind.CONSTRUCTOR)
                    .returnType(new PrimitiveTypeTree(PrimitiveTypeTree.Kind.VOID));

            final var body = new BlockStatement();

            final var superCall = new MethodInvocationTree()
                    .target(new IdentifierTree("this"))
                    .name(new IdentifierTree("super"));
            superCall.setLineNumber(classDeclaration.getLineNumber());
            superCall.setColumnNumber(classDeclaration.getColumnNumber());

            body.statement(superCall);
            body.statement(new ReturnStatement());

            constructor.body(body);

            classDeclaration.enclosedElement(constructor, 0);
        }

        return super.visitClass(classDeclaration, param);
    }

    private boolean hasConstructor(final ClassDeclaration classDeclaration) {
        return classDeclaration.getEnclosedElements().stream()
                .anyMatch(e -> e.getKind() == Element.Kind.CONSTRUCTOR);
    }

}
