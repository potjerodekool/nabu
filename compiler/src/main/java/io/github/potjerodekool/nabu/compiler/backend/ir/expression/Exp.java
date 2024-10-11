package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

public interface Exp {

    IExpression unEx();

    IStatement unNx();

    IStatement unCx(ILabel t, ILabel f);
}
