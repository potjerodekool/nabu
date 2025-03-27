package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.type.PackageType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;

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
}
