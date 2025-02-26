package io.github.potjerodekool.nabu.compiler.ast.element;

public interface PackageElement extends Element, QualifiedNameable {

    boolean isUnnamed();
}
