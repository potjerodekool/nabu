package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.tree.Tag;

import java.util.Collections;
import java.util.List;

public class Unop extends IExpression {

    private final Tag tag;
    private final IExpression expression;

    public Unop(final Tag tag,
                final IExpression expression) {
        this.tag = tag;
        this.expression = expression;
    }

    public Tag getTag() {
        return tag;
    }

    public IExpression getExpression() {
        return expression;
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitUnop(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return Collections.singletonList(expression);
    }

    @Override
    public IExpression build(final List<IExpression> kids) {
        return new Unop(tag, kids.getFirst());
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();

        if (tag.isPrefix()) {
            builder.append(tag).append(" ");
        }
        builder.append(expression).append(" ");
        if (!tag.isPrefix()) {
            builder.append(tag);
        }

        return builder.toString();
    }
}
