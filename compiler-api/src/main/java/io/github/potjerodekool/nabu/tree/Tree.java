package io.github.potjerodekool.nabu.tree;

public interface Tree {

    int getLineNumber();

    int getColumnNumber();

    <R, P> R accept(TreeVisitor<R, P> visitor, P param);

}
