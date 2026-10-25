package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.impl.CRecordPattern;

import java.util.List;

/**
 * A record pattern (JLS 14.30.2).
 * <p> {@code Point(int x, int y)} deconstructs a record and binds
 * its components to variables.</p>
 */
public sealed interface RecordPattern extends Pattern permits CRecordPattern {

    ExpressionTree getRecordType();

    List<Pattern> getPatterns();

    static RecordPattern create(final ExpressionTree recordType,
                                final List<Pattern> patterns,
                                final int lineNumber,
                                final int columnNumber) {
        return new CRecordPattern(recordType, patterns, lineNumber, columnNumber);
    }
}
