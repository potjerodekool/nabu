package io.github.potjerodekool.nabu.lang.jpa.support;

public sealed interface Join<Z, X> permits InnerJoin, LeftJoin, RightJoin {
}
