package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.CompilerOptions;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.ModuleDeclaration;

public interface ByteCodeGenerator {


    void generate(ClassDeclaration clazz,
                  CompilerOptions options);

    void generate(ModuleDeclaration moduleDeclaration,
                  CompilerOptions options);

    byte[] getBytecode();
}
