package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.ast.element.ExecutableElement;
import io.github.potjerodekool.nabu.compiler.tree.CModifiers;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;

import java.util.List;

public interface Function extends Tree {

    ExecutableElement getMethodSymbol();

    void setMethodSymbol(ExecutableElement methodSymbol);

    String getSimpleName();

    Kind getKind();

    CModifiers getModifiers();

    ExpressionTree getDefaultValue();

    VariableDeclaratorTree getReceiverParameter();

    List<? extends VariableDeclaratorTree> getParameters();

    ExpressionTree getReturnType();

    List<? extends Tree> getThrownTypes();

    BlockStatementTree getBody();

    default boolean hasFlag(final long flag) {
        return getModifiers().hasFlag(flag);
    }

    FunctionBuilder builder();

}


