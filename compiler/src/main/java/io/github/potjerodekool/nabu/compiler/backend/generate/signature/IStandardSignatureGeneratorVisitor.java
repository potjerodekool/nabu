package io.github.potjerodekool.nabu.compiler.backend.generate.signature;

import io.github.potjerodekool.nabu.compiler.backend.ir.type.ITypeVariable;

public class IStandardSignatureGeneratorVisitor extends AbstractSignatureGeneratorVisitor {

    public static final IStandardSignatureGeneratorVisitor INSTANCE = new IStandardSignatureGeneratorVisitor();

    private IStandardSignatureGeneratorVisitor() {
    }

    @Override
    public String visitTypeVariableType(final ITypeVariable typeVariable, final Void param) {
        final var name = typeVariable.getName();

        if (typeVariable.getUpperBound() != null) {
            return "T" + name + ":" + typeVariable.getUpperBound().accept(this, param);
        } else if (typeVariable.getLowerBound() != null) {
            return "T" + name + ":" + typeVariable.getLowerBound().accept(this, param);
        } else {
            return "T" + name + ";";
        }
    }

}
