package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import java.util.List;

public interface Call {

    List<IExpression> kids();

    IExpression build(List<IExpression> kids);
}
