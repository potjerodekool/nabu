package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.type.VariableType;
import io.github.potjerodekool.nabu.util.Types;

public final class PhaseUtils {

    private final CompilerContext compilerContext;
    private final Types types;

    public PhaseUtils(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
        this.types = compilerContext.getClassElementLoader().getTypes();
    }

    public VariableSymbol createVariable(final VariableDeclaratorTree variableDeclaratorStatement) {
        final var identifier = variableDeclaratorStatement.getName();
        var type = variableDeclaratorStatement.getVariableType().getType();

        if (type instanceof VariableType) {
            if (variableDeclaratorStatement.getValue() == null) {
                type = types.getErrorType("error");
            } else {
                final var interferedType = compilerContext.getTreeUtils().typeOf(variableDeclaratorStatement.getValue());
                type = types.getVariableType(interferedType);
            }
            variableDeclaratorStatement.getVariableType().setType(type);
        } else if (type == null) {
            type = types.getErrorType("error");
        }

        return new VariableSymbolBuilderImpl()
                .kind(toElementKind(variableDeclaratorStatement.getKind()))
                .simpleName(identifier.getName())
                .type(type)
                .flags(variableDeclaratorStatement.getFlags())
                .build();
    }

    public ElementKind toElementKind(final Kind kind) {
        return ElementKind.valueOf(kind.name());
    }
}
