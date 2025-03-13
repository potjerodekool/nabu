package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;

import java.util.HashMap;
import java.util.Map;

public class TranslateContext {

    ClassDeclaration classDeclaration;
    Function function;
    Frame frame;

    TranslateContext() {
    }
}
