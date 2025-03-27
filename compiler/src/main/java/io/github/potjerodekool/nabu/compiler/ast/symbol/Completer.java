package io.github.potjerodekool.nabu.compiler.ast.symbol;

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

    void complete(Symbol symbol) throws CompleteException;

    default boolean isTerminal() {
        return false;
    }
}
