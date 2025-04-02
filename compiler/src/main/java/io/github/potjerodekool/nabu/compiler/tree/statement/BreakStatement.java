package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.Tree;

public interface BreakStatement extends StatementTree {

    Tree getTarget();
}
