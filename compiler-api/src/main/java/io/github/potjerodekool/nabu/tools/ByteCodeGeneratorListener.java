package io.github.potjerodekool.nabu.tools;

public interface ByteCodeGeneratorListener {


    void generated(FileObject sourceFile,
                   PathFileObject classFile,
                   String className);
}
