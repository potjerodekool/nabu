package io.github.potjerodekool.nabu.compiler.backend.ir.statement;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.IExpression;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;
import io.github.potjerodekool.nabu.tree.Tag;

import java.util.Arrays;
import java.util.List;

public class CJump extends IStatement {

    private Tag tag;
    private IExpression left;
    private IExpression right;
    private final ILabel trueLabel;
    private final ILabel falseLabel;

    public CJump(final Tag tag,
                 final IExpression l,
                 final IExpression r,
                 final ILabel t,
                 final ILabel f) {
        this.tag = tag;
        this.left = l;
        this.right = r;
        this.trueLabel = t;
        this.falseLabel = f;
    }

    public Tag getTag() {
        return tag;
    }

    public void setTag(final Tag tag) {
        this.tag = tag;
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
        return left + " " + tag + " " + right + " then " + trueLabel + " else " + falseLabel + '\n';
    }

    @Override
    public List<IExpression> kids() {
        return List.of(left, right);
    }

    @Override
    public IStatement build(final List<IExpression> kids) {
        return new
                CJump(tag,kids.getFirst(), kids.getLast(), trueLabel, falseLabel);
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
        return new CJump(not(tag), left, right, falseLabel, trueLabel);
    }

    public IStatement changeFalseLabel(final ILabel newFalseLabel) {
        return new CJump(tag, left, right, trueLabel, newFalseLabel);
    }

    private Tag not(final Tag tag) {
        return switch (tag) {
            case EQ -> Tag.NE;
            case NE -> Tag.EQ;
            case GE -> Tag.LT;
            case LT -> Tag.GE;
            case GT -> Tag.LE;
            case LE -> Tag.GT;
            default -> throw new IllegalArgumentException(String.valueOf(tag));
        };
    }
}


