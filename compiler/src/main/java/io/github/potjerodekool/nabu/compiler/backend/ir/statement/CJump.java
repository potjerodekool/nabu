package io.github.potjerodekool.nabu.compiler.backend.ir.statement;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.IExpression;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

import java.util.Arrays;
import java.util.List;

public class CJump extends IStatement {

    public static final Operator EQUALS = Operator.EQUALS;
    public static final Operator NOT_EQUALS = Operator.NOT_EQUALS;
    public static final Operator GE = Operator.GE;
    public static final Operator LT = Operator.LT;
    public static final Operator LE = Operator.LE;
    public static final Operator GT = Operator.GT;
    public static final Operator UGT = Operator.UGT;
    public static final Operator ULE = Operator.ULE;
    public static final Operator ULT = Operator.ULT;
    public static final Operator UGE = Operator.UGE;

    private Operator operator;
    private IExpression left;
    private IExpression right;
    private final ILabel trueLabel;
    private final ILabel falseLabel;

    public CJump(final Operator operator,
                 final IExpression l, final IExpression r,
                 final ILabel t,
                 final ILabel f) {
        this.operator = operator;
        this.left = l;
        this.right = r;
        this.trueLabel = t;
        this.falseLabel = f;
    }

    public Operator getOperator() {
        return operator;
    }

    public void setOperator(final Operator operator) {
        this.operator = operator;
    }

    public IExpression getLeft() {
        return left;
    }

    public void setLeft(final IExpression left) {
        this.left = left;
    }

    public IExpression getRight() {
        return right;
    }

    public void setRight(final IExpression right) {
        this.right = right;
    }

    public ILabel getTrueLabel() {
        return trueLabel;
    }

    public ILabel getFalseLabel() {
        return falseLabel;
    }

    @Override
    public <P> void accept(final CodeVisitor<P> visitor, final P param) {
        visitor.visitCJump(this, param);
    }

    @Override
    public String toString() {
        return left + " " + operator + " " + right + " then " + trueLabel + " else " + falseLabel + '\n';
    }

    @Override
    public List<IExpression> kids() {
        return List.of(left, right);
    }

    @Override
    public IStatement build(final List<IExpression> kids) {
        return new
                CJump(operator, kids.getFirst(), kids.getLast(), trueLabel, falseLabel);
    }

    @Override
    public boolean isJump() {
        return true;
    }

    @Override
    public List<ILabel> getJumpTargets() {
        return Arrays.asList(trueLabel, falseLabel);
    }

    public IStatement flip() {
        return new CJump(not(operator), left, right, falseLabel, trueLabel);
    }

    public IStatement changeFalseLabel(final ILabel newFalseLabel) {
        return new CJump(operator, left, right, trueLabel, newFalseLabel);
    }

    private Operator not(final Operator operator) {
        return switch (operator) {
            case EQUALS -> Operator.NOT_EQUALS;
            case NOT_EQUALS -> Operator.EQUALS;
            case GE -> Operator.LT;
            case LT -> Operator.GE;
            case GT -> Operator.LE;
            case LE -> Operator.GT;
            default -> throw new IllegalArgumentException(String.valueOf(operator));
        };
    }
}


