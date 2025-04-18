package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.compiler.type.VariableType;
import io.github.potjerodekool.nabu.compiler.util.Types;

import static io.github.potjerodekool.nabu.compiler.resolve.TreeUtils.typeOf;

public final class PhaseUtils {

    private final Types types;

    public PhaseUtils(final Types types) {
        this.types = types;
    }

    public VariableSymbol createVariable(final VariableDeclaratorTree variableDeclaratorStatement) {
        final var identifier = variableDeclaratorStatement.getName();
        var type = variableDeclaratorStatement.getType().getType();

        if (type instanceof VariableType) {
            if (variableDeclaratorStatement.getValue() == null) {
                type = types.getErrorType("error");
            } else {
                final var interferedType = typeOf(variableDeclaratorStatement.getValue());
                type = types.getVariableType(interferedType);
            }
            variableDeclaratorStatement.getType().setType(type);
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
