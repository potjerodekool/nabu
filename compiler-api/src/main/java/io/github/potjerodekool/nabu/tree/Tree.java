package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.type.TypeMirror;

public interface Tree {

    int getLineNumber();

    int getColumnNumber();

    TypeMirror getType();

    void setType(TypeMirror type);

    <R, P> R accept(TreeVisitor<R, P> visitor, P param);

}
