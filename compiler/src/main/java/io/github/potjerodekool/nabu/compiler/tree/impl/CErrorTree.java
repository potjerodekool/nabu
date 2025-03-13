package io.github.potjerodekool.nabu.compiler.tree.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.ErrorTree;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public class CErrorTree extends CTree implements ErrorTree {

    public CErrorTree(final int lineNumber,
                      final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
    }
    @Override

    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitErroneous(this, param);
    }

    @Override
    public Element getSymbol() {
        return null;
    }

    @Override
    public void setSymbol(final Element symbol) {

    }

    @Override
    public TypeMirror getType() {
        return null;
    }

    @Override
    public void setType(final TypeMirror type) {
    }
}
