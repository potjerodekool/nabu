package io.github.potjerodekool.nabu.tree;

public interface PatternTreeVisitor<R, P> {

    R acceptTree(Tree tree,
                 P param);
}
