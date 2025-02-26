package io.github.potjerodekool.nabu.compiler.ast.element;

public interface ElementVisitor<R, P> {
    R visitExecutableElement(ExecutableElement methodSymbol, P p);

    R visitTypeParameterElement(TypeParameterElement typeParameterElement, P p);
}
