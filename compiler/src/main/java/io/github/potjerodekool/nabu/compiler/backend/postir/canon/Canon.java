package io.github.potjerodekool.nabu.compiler.backend.postir.canon;


import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.Seq;

import java.util.ArrayList;
import java.util.List;

import static io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement.seq;
import static io.github.potjerodekool.nabu.compiler.util.CollectionUtils.headAndTailList;

public class Canon {

    public static List<IStatement> linearize(final IStatement statement) {
        return linear(doStatement(statement), new ArrayList<>());
    }

    private static IStatement doStatement(final IStatement statement) {
        if (statement instanceof Seq seq) {
            return doSeqStatement(seq);
        } else {
            return statement;
        }
    }

    private static IStatement doSeqStatement(final Seq s) {
        return seq(doStatement(s.getLeft()), doStatement(s.getRight()));
    }

    private static List<IStatement> linear(final Seq s, final List<IStatement> l) {
        return linear(s.getLeft(), linear(s.getRight(), l));
    }

    private static List<IStatement> linear(final IStatement s, final List<IStatement> l) {
        return s instanceof Seq seq ? linear(seq, l) : headAndTailList(s, l);
    }

}
