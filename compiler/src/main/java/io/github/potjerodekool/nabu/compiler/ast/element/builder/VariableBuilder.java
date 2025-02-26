package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public class VariableBuilder extends AbstractElementBuilder<VariableBuilder> {

    private TypeMirror type;

    public VariableBuilder() {
        kind = ElementKind.VARIABLE;
    }

    public VariableBuilder type(final TypeMirror type) {
        this.type = type;
        return this;
    }

    public VariableElement build() {
        final var variable = new VariableSymbol(
                kind,
                modifiers,
                name,
                null
        );
        variable.setType(type);
        return variable;
    }

    @Override
    protected VariableBuilder self() {
        return this;
    }
}
