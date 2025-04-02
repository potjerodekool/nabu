package io.github.potjerodekool.nabu.compiler.ast.symbol;

public interface Completer {

    Completer NULL_COMPLETER = symbol -> {};

    void complete(Symbol symbol) throws CompleteException;

}
