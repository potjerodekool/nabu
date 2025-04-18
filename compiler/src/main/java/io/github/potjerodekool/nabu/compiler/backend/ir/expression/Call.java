package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.InvocationType;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import java.util.List;
import java.util.StringJoiner;

import static io.github.potjerodekool.nabu.compiler.util.CollectionUtils.headAndTailList;

public class Call extends IExpression {

    protected final InvocationType invocationType;
    protected final Name function;
    protected final IType returnType;
    protected final List<IType> paramTypes;
    protected final List<IExpression> args;

    // non dynamic
    protected final IType owner;

    //lambda
    protected final Call lambdaFunctionCall;
    protected final Call lambdaCall;

    /**
     * Create a non dynamic call
     * @param invocationType
     * @param owner
     * @param function
     * @param returnType
     * @param paramTypes
     * @param args
     */
    public Call(final InvocationType invocationType,
                final IType owner,
                final Name function,
                final IType returnType,
                final List<IType> paramTypes,
                final List<IExpression> args) {
        this(invocationType, owner, function, returnType, paramTypes, args, null, null);
    }

    /**
     * Create a dynamic call.
     * @param function
     * @param returnType
     * @param paramTypes
     * @param args
     * @param lambdaFunctionCall
     * @param lambdaCall
     */
    public Call(final Name function,
                final IType returnType,
                final List<IType> paramTypes,
                final List<IExpression> args,
                final Call lambdaFunctionCall,
                final Call lambdaCall) {
        this(InvocationType.DYNAMIC, null, function, returnType, paramTypes, args, lambdaFunctionCall, lambdaCall);
    }

    private Call(final InvocationType invocationType,
                 final IType owner,
                 final Name function,
                 final IType returnType,
                 final List<IType> paramTypes,
                 final List<IExpression> args,
                 final Call lambdaFunctionCall,
                 final Call lambdaCall) {
        this.invocationType = invocationType;
        this.owner = owner;
        this.function = function;
        this.returnType = returnType;
        this.paramTypes = paramTypes;
        this.args = args;
        this.lambdaFunctionCall = lambdaFunctionCall;
        this.lambdaCall = lambdaCall;
    }

    public InvocationType getInvocationType() {
        return invocationType;
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

    public IType getOwner() {
        return owner;
    }

    public Call getLambdaFunctionCall() {
        return lambdaFunctionCall;
    }

    public Call getLambdaCall() {
        return lambdaCall;
    }

    @Override
    public List<IExpression> kids() {
        return headAndTailList(function, args);
    }

    @Override
    public IExpression build(final List<IExpression> kids) {
        if (invocationType == InvocationType.DYNAMIC) {
            return new Call(
                    (Name) kids.removeFirst(),
                    returnType,
                    paramTypes,
                    kids,
                    lambdaFunctionCall,
                    lambdaCall
            );
        } else {
            return new Call(invocationType, owner, (Name) kids.removeFirst(), returnType, paramTypes, kids);
        }
    }

    @Override
    public IType getType() {
        return getReturnType();
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitCall(this, param);
    }

    @Override
    public String toString() {
        return invocationType == InvocationType.DYNAMIC
                ? toStringDynamic()
                : toStringNonDynamic();
    }

    private String toStringDynamic() {
        return super.toString();
    }

    public String toStringNonDynamic() {
        final StringBuilder builder = new StringBuilder();

        final boolean staticOrSpecial = invocationType == InvocationType.STATIC ||
                invocationType == InvocationType.SPECIAL;

        if (!staticOrSpecial) {
            builder.append(args.getFirst()).append(".");
        }
        builder.append(function);

        builder.append("(");

        final int off = staticOrSpecial ? 0 : 1;

        final StringJoiner joiner = new StringJoiner(",");

        for (int i = off; i < args.size(); i++) {
            joiner.add(args.get(i).toString());
        }
        builder.append(joiner);
        builder.append(")");

        return builder.toString();
    }

}
