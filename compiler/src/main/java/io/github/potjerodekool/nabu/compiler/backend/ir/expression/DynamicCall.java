package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import java.util.List;

import static io.github.potjerodekool.nabu.compiler.util.CollectionUtils.headAndTailList;

public final class DynamicCall extends IExpression implements Call {

    private final Name function;
    private final IType returnType;
    private final List<IType> paramTypes;
    private final List<IExpression> args;


    //lambda
    private final DefaultCall lambdaFunctionCall;
    private final DefaultCall lambdaCall;

    public DynamicCall(final Name function,
                       final IType returnType,
                       final List<IType> paramTypes,
                       final List<IExpression> args,
                       final DefaultCall lambdaFunctionCall,
                       final DefaultCall lambdaCall) {
        this.function = function;
        this.returnType = returnType;
        this.paramTypes = paramTypes;
        this.args = args;

        this.lambdaFunctionCall = lambdaFunctionCall;
        this.lambdaCall = lambdaCall;
    }

    public Name getFunction() {
        return function;
    }

    public IType getReturnType() {
        return returnType;
    }

    public List<IType> getParamTypes() {
        return paramTypes;
    }

    public List<IExpression> getArgs() {
        return args;
    }

    public DefaultCall getLambdaFunctionCall() {
        return lambdaFunctionCall;
    }

    public DefaultCall getLambdaCall() {
        return lambdaCall;
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitCall(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return headAndTailList(function, args);
    }

    @Override
    public IExpression build(final List<IExpression> kids) {
        return new DynamicCall(
                (Name) kids.removeFirst(),
                returnType,
                paramTypes,
                kids,
                lambdaFunctionCall,
                lambdaCall
        );
    }

    @Override
    public IType getType() {
        return getReturnType();
    }
}
