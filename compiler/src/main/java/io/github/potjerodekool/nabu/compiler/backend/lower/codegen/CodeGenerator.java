package io.github.potjerodekool.nabu.compiler.backend.lower.codegen;

import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;

public interface CodeGenerator {
    void generateCode(ClassDeclaration classDeclaration);
}
