package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.ModuleDeclaration;

public interface ByteCodeGenerator {


    void generate(ClassDeclaration clazz,
                  Object param);

    void generate(ModuleDeclaration moduleDeclaration,
                  Object param);

    byte[] getBytecode();
}
