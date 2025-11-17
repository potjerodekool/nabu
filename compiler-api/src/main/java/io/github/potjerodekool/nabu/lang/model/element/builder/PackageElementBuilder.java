package io.github.potjerodekool.nabu.lang.model.element.builder;

import io.github.potjerodekool.nabu.lang.model.element.PackageElement;

public interface PackageElementBuilder<P extends PackageElement> extends ElementBuilder<PackageElementBuilder<P>> {

    PackageElement createUnnamed();
}
