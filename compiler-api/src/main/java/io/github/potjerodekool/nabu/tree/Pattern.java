package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.impl.CPattern;

/**
 * Root interface for patterns.
 */
public sealed interface Pattern extends Tree permits TypePattern, CPattern {
}
