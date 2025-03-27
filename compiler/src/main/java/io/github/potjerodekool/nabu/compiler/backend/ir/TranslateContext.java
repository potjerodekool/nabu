package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;

public class TranslateContext {

    ClassDeclaration classDeclaration;
    Function function;
    Frame frame;

    TranslateContext() {
    }
}
