package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.backend.Backend;
import io.github.potjerodekool.nabu.backend.CompileException;
import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.ir.IRModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ASMBackend implements Backend {
    @Override
    public void compile(final IRModule module,
                        final CompileOptions opts,
                        final Path output) throws CompileException {
        final var emitter = new ASMByteCodeEmitter();
        emitter.emit(module);
        final var bytecode = emitter.getBytecode();

        try {
            Files.write(output, bytecode);
            System.out.println(output.toAbsolutePath());
        } catch (IOException e) {
            throw new CompileException("Error while writing bytecode.", e);
        }
    }
}
