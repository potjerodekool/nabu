package io.github.potjerodekool.nabu.compiler.backend.translate;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.ir.IRBuilder;
import io.github.potjerodekool.nabu.ir.IRModule;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.ReturnStatementTree;
import io.github.potjerodekool.nabu.type.*;

import java.util.List;

public class TranslateV2 extends AbstractTreeVisitor<Object, Object> {

    private final TypeTranslator typeTranslator = new TypeTranslator();

    private IRBuilder builder;

    private IRValue visit(final Tree tree) {
        if (tree instanceof BlockStatementTree blockStatementTree) {
            visitBlock(blockStatementTree);
        } else if (tree instanceof ReturnStatementTree returnStatementTree) {
            visitReturnStatement(returnStatementTree);
        }

        return null;
    }

    private void visitBlock(final BlockStatementTree blockStatementTree) {
        blockStatementTree.getStatements().forEach(this::visit);
    }

    private void visitReturnStatement(final ReturnStatementTree returnStatementTree) {
        if (returnStatementTree.getExpression() != null) {
            builder.emitReturn(visit(returnStatementTree.getExpression()));
        } else {
            builder.emitReturn(null);
        }
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration, final Object param) {
        builder = new IRBuilder(classDeclaration.getSimpleName());
        return null;
    }

    public IRModule getModule() {
        return builder.build();
    }

    @Override
    public Object visitFunction(final Function function, final Object param) {
        final var method = (MethodSymbol) function.getMethodSymbol();
        final var returnType = method.getReturnType().accept(typeTranslator, param);
        final List<IRValue> params = method.getParameters().stream()
                .map(methodParam -> {
                    final var paramType = methodParam.asType().accept(typeTranslator, param);
                    return (IRValue) new IRValue.Temp(methodParam.getSimpleName(), paramType);
                })
                .toList();

        builder.beginFunction(
                function.getSimpleName(),
                returnType,
                params,
                false
        );

        if (function.getBody() != null) {
            visit(function.getBody());
        }

        builder.endFunction();

        return null;
    }
}

class TypeTranslator implements TypeVisitor<IRType, Object> {

    @Override
    public IRType visitUnknownType(final TypeMirror typeMirror, final Object param) {
        throw new TodoException();
    }

    @Override
    public IRType visitNoType(final NoType noType, final Object param) {
        if (noType.getKind() == TypeKind.VOID) {
            return new IRType.Void();
        }
        return TypeVisitor.super.visitNoType(noType, param);
    }

    @Override
    public IRType visitArrayType(final ArrayType arrayType, final Object param) {
        final var elementType = arrayType.getComponentType().accept(this, param);
        return new IRType.Array(elementType,0);
    }

    @Override
    public IRType visitDeclaredType(final DeclaredType declaredType, final Object param) {
        return new IRType.CustomType(
                declaredType.asTypeElement().getQualifiedName()
        );
    }
}