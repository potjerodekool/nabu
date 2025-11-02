package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.builder.ElementBuilder;

public class PackageSymbolBuilder implements ElementBuilder<PackageSymbolBuilder> {

    private Element enclosingElement;

    private String simpleName;

    public PackageSymbolBuilder(final PackageSymbol packageSymbol) {
        this.enclosingElement = packageSymbol.getEnclosingElement();
        this.simpleName = packageSymbol.getSimpleName();
    }

    @Override
    public Element getEnclosingElement() {
        return enclosingElement;
    }

    @Override
    public PackageSymbolBuilder enclosingElement(final Element enclosingElement) {
        this.enclosingElement = enclosingElement;
        return this;
    }

    @Override
    public String getSimpleName() {
        return simpleName;
    }

    @Override
    public PackageSymbolBuilder simpleName(final String name) {
        this.simpleName = name;
        return this;
    }

    @Override
    public PackageSymbol build() {
        return new PackageSymbol(this);
    }

    @Override
    public PackageSymbolBuilder kind(final ElementKind elementKind) {
        return this;
    }

    @Override
    public PackageSymbolBuilder flags(final long flags) {
        return this;
    }
}
