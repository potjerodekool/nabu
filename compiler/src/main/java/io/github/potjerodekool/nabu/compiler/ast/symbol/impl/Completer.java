package io.github.potjerodekool.nabu.compiler.ast.symbol.impl;

public interface Completer {

    Completer NULL_COMPLETER = new Completer() {
        @Override
        public void complete(final Symbol symbol) throws CompleteException {
        }

        @Override
        public boolean isTerminal() {
            return true;
        }
    };

    Completer DEFAULT_COMPLETER = symbol -> {
    };

    void complete(Symbol symbol) throws CompleteException;

    default boolean isTerminal() {
        return false;
    }

}
