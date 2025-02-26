package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import java.util.List;

public sealed interface Call permits DefaultCall, DynamicCall {

    List<IExpression> kids();

    IExpression build(List<IExpression> kids);
}
