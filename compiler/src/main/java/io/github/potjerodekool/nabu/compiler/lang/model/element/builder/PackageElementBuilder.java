package io.github.potjerodekool.nabu.compiler.lang.model.element.builder;

import io.github.potjerodekool.nabu.compiler.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.compiler.lang.model.element.PackageElement;

public interface PackageElementBuilder<P extends PackageElement> extends ElementBuilder<PackageElementBuilder<P>> {

    PackageElement createUnnamed();

    PackageElementBuilder module(ModuleElement module);
}
