package io.github.potjerodekool.nabu.testing;

import io.github.potjerodekool.nabu.tree.Tree;

@FunctionalInterface
public interface TreeProcessor {

    boolean process(Tree tree);
}
