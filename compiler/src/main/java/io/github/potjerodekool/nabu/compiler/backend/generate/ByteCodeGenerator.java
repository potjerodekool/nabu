package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.ModuleDeclaration;

public interface ByteCodeGenerator {


    void generate(ClassDeclaration clazz,
                  CompilerOptions param);

    void generate(ModuleDeclaration moduleDeclaration,
                  CompilerOptions param);

    byte[] getBytecode();
}
