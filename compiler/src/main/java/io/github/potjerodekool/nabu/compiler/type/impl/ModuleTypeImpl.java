package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.type.ModuleType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;

public class ModuleTypeImpl extends AbstractType implements ModuleType {

    public ModuleTypeImpl(final ModuleSymbol typeElement) {
        super(typeElement);
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.MODULE;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitModule(this, param);
    }

    @Override
    public String getClassName() {
        return "";
    }
}
