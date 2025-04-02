package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.Tree;

public interface ContinueStatement extends StatementTree{

    Tree getTarget();
}
