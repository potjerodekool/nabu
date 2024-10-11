package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.Operator;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.List;
import java.util.Objects;

public class BinOp extends IExpression {

    public enum Oper {
        PLUS,
        MIN,
        MUL,
        DIV,
        REM,
        LT,
        LE,
        EQ,
        NE,
        GE,
        GT,
        AND,
        OR,
        LSHIFT,
        RSHITT,
        ARSHIFT,
        XOR,
        B_AND,
        BI_OR,
        DSHIFT_LEFT,
        DSHIFT_RIGHT,
        TSHIFT_RIGHT,
        INCLUSIVE_OR
    }

    private final Oper oper;
    private IExpression left;
    private IExpression right;

    public static final Oper PLUS = Oper.PLUS;
    public static final Oper MIN = Oper.MIN;
    public static final Oper MUL = Oper.MUL;
    public static final Oper DIV = Oper.DIV;
    public static final Oper REM = Oper.REM;
    public static final Oper AND = Oper.AND;
    public static final Oper OR = Oper.OR;
    public static final Oper LSHIFT = Oper.LSHIFT;
    public static final Oper RSHIFT = Oper.RSHITT;
    public static final Oper ARSHIFT = Oper.ARSHIFT;
    public static final Oper XOR = Oper.XOR;
    public static final Oper EQ = Oper.EQ;

    public BinOp(final IExpression l, final Oper oper, final IExpression r) {
        check(l);
        check(oper);
        check(r);
        this.oper = oper;
        left = l;
        right = r;
    }

    private void check(final Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitBinop(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return List.of(left,right);
    }

    @Override
    public IExpression build(final List<IExpression> kids) {
        return new BinOp(kids.getFirst(), oper, kids.getLast());
    }

    public Oper getOper() {
        return oper;
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

    public static IExpression binop(final BinOp.Oper binop,
                                    final IExpression left, final IExpression right) {
        return new BinOp(left, binop, right);
    }

    public static BinOp.Oper opper(final Operator operator) {
        return switch (operator) {
            case PLUS -> Oper.PLUS;
            case MIN -> Oper.MIN;
            case MUL -> Oper.MUL;
            case DIV -> Oper.DIV;
            case REM -> Oper.REM;
            case LT -> Oper.LT;
            case LE -> Oper.LE;
            case GE -> Oper.GE;
            case GT -> Oper.GT;
            case EQUALS -> Oper.EQ;
            case NOT_EQUALS -> Oper.NE;
            case B_AND -> Oper.B_AND;
            case BI_OR -> Oper.BI_OR;
            case AND -> Oper.AND;
            case OR -> Oper.OR;
            case DSHIFT_LEFT -> Oper.DSHIFT_LEFT;
            case INCLUSIVE_OR -> Oper.INCLUSIVE_OR;
            default -> throw new IllegalArgumentException("" + operator);
        };
    }

    @Override
    public String toString() {
        return left + " " + oper + " " + right;
    }
}
