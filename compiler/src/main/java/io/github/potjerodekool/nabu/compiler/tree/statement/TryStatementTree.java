package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.CatchTree;
import io.github.potjerodekool.nabu.compiler.tree.Tree;

import java.util.List;

public interface TryStatementTree extends StatementTree {

    BlockStatementTree getBody();

    List<CatchTree> getCatchers();

    BlockStatementTree getFinalizer();

    List<Tree> getResources();
}
