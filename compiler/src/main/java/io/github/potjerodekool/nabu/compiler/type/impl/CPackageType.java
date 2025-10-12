package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.type.PackageType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeVisitor;

import java.util.Objects;

public class CPackageType extends AbstractType implements PackageType {

    public CPackageType(final PackageSymbol packageSymbol) {
        super(packageSymbol);
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.PACKAGE;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitPackageType(this, param);
    }

    @Override
    public String getClassName() {
        return "Package";
    }

    @Override
    public PackageSymbol asElement() {
        return (PackageSymbol) super.asElement();
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof CPackageType other
                && Objects.equals(asElement().getQualifiedName(), other.asElement().getQualifiedName());
    }

    @Override
    public int hashCode() {

        return asElement().getQualifiedName().hashCode();
    }
}
