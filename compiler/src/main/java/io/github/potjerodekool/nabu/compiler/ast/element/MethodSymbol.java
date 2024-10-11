package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.backend.ir.ProcFrag;
import io.github.potjerodekool.nabu.compiler.type.MethodType;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableMethodType;

import java.util.ArrayList;
import java.util.List;

public class MethodSymbol extends AbstractSymbol {

    private final MutableMethodType methodType = new MutableMethodType(this);

    private final List<VariableElement> parameters = new ArrayList<>();

    private ProcFrag frag;

    public MethodSymbol(final ElementKind kind,
                        final String name,
                        final AbstractSymbol owner) {
        super(kind, name, owner);
    }

    public ProcFrag getFrag() {
        return frag;
    }

    public void setFrag(final ProcFrag frag) {
        this.frag = frag;
    }

    public MutableMethodType getMethodType() {
        return methodType;
    }

    public List<VariableElement> getParameters() {
        return parameters;
    }

    public void addParameter(final VariableElement parameter) {
        this.parameters.add(parameter);
    }

    @Override
    public void setEnclosingElement(final Element enclosingElement) {
        super.setEnclosingElement(enclosingElement);
        methodType.setOwner((TypeElement) enclosingElement);
    }
}
