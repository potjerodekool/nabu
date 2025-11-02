package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.statement.builder.StatementTreeBuilder;

public interface StatementTree extends Tree {

    StatementTreeBuilder<?> builder();
}
