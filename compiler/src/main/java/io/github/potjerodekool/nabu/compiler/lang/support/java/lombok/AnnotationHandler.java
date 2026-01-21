package io.github.potjerodekool.nabu.compiler.lang.support.java.lombok;

import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

public interface AnnotationHandler {
    String getAnnotationName();

    default void handle(final ClassDeclaration classDeclaration) {
    }

    default void handle(VariableDeclaratorTree field,
                ClassDeclaration classDeclaration) {
    }
}
