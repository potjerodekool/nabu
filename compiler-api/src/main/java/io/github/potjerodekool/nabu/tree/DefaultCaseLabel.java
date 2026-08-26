package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.impl.CDefaultCaseLabel;

/**
 * A default case label.
 * <p> </p>
 * switch (value) {
 *     default -> {
 * <p> </p>
 *     }
 * }
 */
public sealed interface DefaultCaseLabel extends CaseLabel permits CDefaultCaseLabel {

    static DefaultCaseLabel create(final int lineNumber,
                                   final int columnNumber) {
        return new CDefaultCaseLabel(lineNumber, columnNumber);
    }
}
