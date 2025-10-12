package io.github.potjerodekool.nabu.compiler.backend.ir.impl;

import io.github.potjerodekool.nabu.compiler.backend.ir.Frame;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;

public class TranslateContext {

    ClassDeclaration classDeclaration;
    Function function;
    Frame frame;

    TranslateContext() {
    }
}
