package io.github.potjerodekool.nabu.lang.jpa.support;

/**
 * Root interface for join.
 * @param <Z> Source of the Join
 * @param <X> Taget of the join
 */
public sealed interface Join<Z, X> permits InnerJoin, LeftJoin, RightJoin {
}
