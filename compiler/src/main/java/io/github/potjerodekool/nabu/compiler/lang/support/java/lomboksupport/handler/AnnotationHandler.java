package io.github.potjerodekool.nabu.compiler.lang.support.java.lomboksupport.handler;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;

public interface AnnotationHandler {
    String getAnnotationName();

    default void handle(final ClassSymbol classSymbol) {
    }

    default void handle(VariableSymbol field,
                        ClassSymbol classSymbol) {
    }
}
