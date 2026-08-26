package io.github.potjerodekool.nabu.tools;

import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;

public interface Modules {

    ModuleElement getNoModule();

    ModuleElement getUnnamedModule();

    ModuleElement getJavaBase();

    ModuleElement getModule(String name);
}
