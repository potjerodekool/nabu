package io.github.potjerodekool.nabu.lang.model.element;

import io.github.potjerodekool.nabu.resolve.scope.WritableScope;

public interface PackageElement extends Element, QualifiedNameable {

    ModuleElement getModuleSymbol();

    boolean isUnnamed();

    WritableScope getMembers();
}
