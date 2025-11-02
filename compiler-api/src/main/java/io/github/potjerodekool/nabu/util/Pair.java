package io.github.potjerodekool.nabu.util;

/**
 * A tuple of two values.
 *
 * @param first A value.
 * @param second Another value.
 * @param <A> A type.
 * @param <B> Another type.
 */
public record Pair<A,B>(A first, B second) {
}
