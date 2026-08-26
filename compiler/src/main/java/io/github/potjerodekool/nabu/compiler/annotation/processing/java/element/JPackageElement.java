package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.log.Logger;

import javax.lang.model.element.*;

public class JPackageElement extends JElement<io.github.potjerodekool.nabu.lang.model.element.PackageElement> implements PackageElement {

    private final Logger logger = Logger.getLogger(getClass().getName());

    public JPackageElement(final io.github.potjerodekool.nabu.lang.model.element.PackageElement packageSymbol) {
        super(packageSymbol);
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitPackage(this, p);
    }

    @Override
    public boolean isUnnamed() {
        return getOriginal().isUnnamed();
    }

}
