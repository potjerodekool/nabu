package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.Options;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;

public interface ByteCodeGenerator {


    void generate(ClassDeclaration clazz,
                  Options options);

    byte[] getBytecode();
}
