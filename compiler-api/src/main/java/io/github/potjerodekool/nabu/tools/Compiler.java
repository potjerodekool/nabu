package io.github.potjerodekool.nabu.tools;

public interface Compiler {
    int compile(CompilerOptions compilerOptions);

    CompilerContext configure(CompilerOptions compilerOptions);
}
