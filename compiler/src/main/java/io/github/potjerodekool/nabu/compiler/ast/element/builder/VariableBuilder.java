package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public class VariableBuilder extends AbstractElementBuilder<VariableBuilder> {

    private TypeMirror type;

    private Object constantValue;

    public VariableBuilder() {
        kind = ElementKind.LOCAL_VARIABLE;
    }

    public VariableBuilder type(final TypeMirror type) {
        this.type = type;
        return this;
    }

    public VariableBuilder constantValue(final Object constantValue) {
        this.constantValue = constantValue;
        return this;
    }

    public VariableElement build() {
        final var variable = new VariableSymbol(
                kind,
                getFlags(),
                name,
                enclosing,
                constantValue
        );
        variable.setType(type);
        return variable;
    }

    @Override
    protected VariableBuilder self() {
        return this;
    }
}
