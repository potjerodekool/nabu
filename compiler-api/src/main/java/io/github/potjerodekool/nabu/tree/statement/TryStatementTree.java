package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.CatchTree;
import io.github.potjerodekool.nabu.tree.Tree;

import java.util.List;

public interface TryStatementTree extends StatementTree {

    BlockStatementTree getBody();

    List<CatchTree> getCatchers();

    BlockStatementTree getFinalizer();

    List<Tree> getResources();
}
