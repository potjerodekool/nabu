package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.type.ModuleType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeVisitor;

import java.util.Objects;

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

    @Override
    public ModuleSymbol asElement() {
        return (ModuleSymbol) super.asElement();
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof ModuleTypeImpl other
                && Objects.equals(
                        asElement().getQualifiedName(),
                other.asElement().getQualifiedName()
        );
    }

    @Override
    public int hashCode() {
        return asElement().getQualifiedName().hashCode();
    }
}
