package io.github.potjerodekool.nabu.lang.jpa;

public sealed interface Join<Z, X> permits InnerJoin, LeftJoin, RightJoin {
}
