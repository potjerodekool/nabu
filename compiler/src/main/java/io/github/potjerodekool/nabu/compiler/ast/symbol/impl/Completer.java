package io.github.potjerodekool.nabu.compiler.ast.symbol.impl;

public interface Completer {

    Completer NULL_COMPLETER = symbol -> {};

    void complete(Symbol symbol) throws CompleteException;

}
