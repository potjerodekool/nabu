package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TypeParameterTree;
import io.github.potjerodekool.nabu.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

import java.util.List;

public interface Function extends Tree {

    ExecutableElement getMethodSymbol();

    void setMethodSymbol(ExecutableElement methodSymbol);

    String getSimpleName();

    Kind getKind();

    Modifiers getModifiers();

    ExpressionTree getDefaultValue();

    VariableDeclaratorTree getReceiverParameter();

    List<? extends VariableDeclaratorTree> getParameters();

    void addParameters(List<VariableDeclaratorTree> parameters);

    void addParameters(int index,
                       List<VariableDeclaratorTree> parameters);

    ExpressionTree getReturnType();

    List<? extends Tree> getThrownTypes();

    BlockStatementTree getBody();

    void setBody(final BlockStatementTree body);

    default boolean hasFlag(final long flag) {
        return getModifiers().hasFlag(flag);
    }

    List<TypeParameterTree> getTypeParameters();

    FunctionBuilder builder();

}


