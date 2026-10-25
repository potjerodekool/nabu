package io.github.potjerodekool.nabu.tree.impl;

import io.github.potjerodekool.nabu.tree.Pattern;
import io.github.potjerodekool.nabu.tree.RecordPattern;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

import java.util.List;

/**
 * Implementation of a record pattern.
 */
public final class CRecordPattern extends CPattern implements RecordPattern {

    private final ExpressionTree recordType;
    private final List<Pattern> patterns;

    public CRecordPattern(final ExpressionTree recordType,
                          final List<Pattern> patterns,
                          final int lineNumber,
                          final int columnNumber) {
        super(lineNumber, columnNumber);
        this.recordType = recordType;
        this.patterns = List.copyOf(patterns);
    }

    @Override
    public ExpressionTree getRecordType() {
        return recordType;
    }

    @Override
    public List<Pattern> getPatterns() {
        return patterns;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitRecordPattern(this, param);
    }
}
