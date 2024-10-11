package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public class VariableElement extends AbstractSymbol {

    private TypeMirror variableType;

    public VariableElement(final ElementKind kind,
                           final String name,
                           final AbstractSymbol owner) {
        super(kind, name, owner);
    }

    public TypeMirror getVariableType() {
        return variableType;
    }

    public void setVariableType(final TypeMirror variableType) {
        this.variableType = variableType;
    }
}
