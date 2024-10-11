package io.github.potjerodekool.nabu.compiler.ast.element;

public interface TypeElement extends Element, QualifiedNameable {

    // QualifiedNameable

    NestingKind getNestingKind();
}
