package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.Tree;

public interface BreakStatement extends StatementTree {

    Tree getTarget();
}
