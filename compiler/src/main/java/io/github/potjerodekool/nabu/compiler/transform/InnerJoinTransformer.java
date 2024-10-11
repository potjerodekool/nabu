package io.github.potjerodekool.nabu.compiler.transform;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.Types;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.expression.CFieldAccessExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.CIdent;
import io.github.potjerodekool.nabu.compiler.tree.expression.LiteralExpression;
import io.github.potjerodekool.nabu.compiler.type.ClassType;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;

public class InnerJoinTransformer extends AbstractJpaTransformer {

    private final ClassElementLoader loader;
    private final Types types;

    protected InnerJoinTransformer(final CompilerContext compilerContext) {
        super(compilerContext);
        this.loader = compilerContext.getClassElementLoader();
        this.types = loader.getTypes();
    }

    @Override
    public Object visitFieldAccessExpression(final CFieldAccessExpression fieldAccessExpression, final Scope param) {
        final var target = (CIdent) fieldAccessExpression.getTarget();
        final var field = (CIdent) fieldAccessExpression.getField();
        final var literalExpression = new LiteralExpression(field.getName());
        final var stringType = loader.resolveType(Constants.STRING);
        literalExpression.setType(stringType);

        final var targetType = (ClassType) resolveType(target);
        final var fieldType = (ClassType) resolveType(field);

        final var fromType = targetType.getParameterTypes().getFirst();
        final var toType = fieldType.getParameterTypes().getFirst();

        final var call = createBuilderCall(
                target,
                "join",
                literalExpression
        );

        final var methodType = call.getMethodType();
        var returnType = (DeclaredType) methodType.getReturnType();

        returnType = types.getDeclaredType(
                (TypeElement) returnType.asElement(),
                fromType,
                toType
        );
        methodType.setReturnType(returnType);

        return call;
    }
}