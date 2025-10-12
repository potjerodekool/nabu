package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.tree.Tag;


import java.util.List;

public class BinOp extends IExpression {

    private final Tag tag;
    private IExpression left;
    private IExpression right;

    public BinOp(final IExpression left,
                 final Tag tag,
                 final IExpression right) {

        if (tag == Tag.ASSIGN && left instanceof TempExpr leftTemp
            && leftTemp.getTemp().getIndex() == -1) {
            throw new IllegalArgumentException();
        }

        this.tag = tag;
        this.left = left;
        this.right = right;
    }

    @Override
    public BinOp flipCompare() {
        final var newLeft = left.flipCompare();
        final var newRight = right.flipCompare();
        final var newTag = flipTag();
        return new BinOp(newLeft, newTag, newRight);
    }

    private Tag flipTag() {
        return switch (tag) {
            case EQ -> Tag.NE;
            case NE, NOT -> Tag.EQ;
            case LT -> Tag.GE;
            case GT -> Tag.LE;
            case LE -> Tag.GT;
            case GE -> Tag.LT;
            default -> tag;
        };
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
        return new BinOp(
                kids.getFirst(),
                tag,
                kids.getLast()
        );
    }

    public Tag getTag() {
        return tag;
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

    @Override
    public String toString() {
        return left + " " + tag + " " + right;
    }
}
