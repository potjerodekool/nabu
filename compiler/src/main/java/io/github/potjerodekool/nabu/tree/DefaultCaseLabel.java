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
}
