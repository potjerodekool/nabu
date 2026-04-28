package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.backend.Backend;
import io.github.potjerodekool.nabu.backend.CompileException;
import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.ir.IRModule;
import io.github.potjerodekool.nabu.tools.TodoException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class ASMBackend implements Backend {
    @Override
    public void compile(final IRModule module,
                        final CompileOptions opts,
                        final Path output) throws CompileException {
        final Path outputFile;

        if (Files.isDirectory(output)) {
            final var classFileName = module.name.replace('.', File.separatorChar) + ".class";
            outputFile = output.resolve(classFileName);;
        } else {
            outputFile = output;
        }

        final var parentDir = outputFile.getParent();

        if (!Files.exists(parentDir)) {
            try {
                Files.createDirectories(parentDir);
            } catch (IOException e) {
                throw new CompileException("Failed to create directory", e);
            }
        }

        final var emitter = new ASMByteCodeEmitter();
        emitter.emit(module);
        final var bytecode = emitter.getBytecode();

        try {
            validate(bytecode);
            Files.write(outputFile, bytecode);
            System.out.println(output.toAbsolutePath());
        } catch (IOException e) {
            throw new CompileException("Error while writing bytecode.", e);
        } catch (final Exception e) {
            final var text = byteCodeToText(bytecode);
            throw new TodoException("Invalid bytecode generated");
        }
    }

    public static void validate(final byte[] bytecode) {
        try {
            final var classReader = new ClassReader(bytecode);
            final var classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS);
            final var classValidator = new CheckClassAdapter(classWriter, true);
            classReader.accept(classValidator, 0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String byteCodeToText(final byte[] bytecode) {
        final var textifier = new Textifier();
        final var classNode = new ClassNode();
        final var visitor = new TraceClassVisitor(classNode, textifier, new PrintWriter(System.out));

        final var reader = new ClassReader(bytecode);
        reader.accept(visitor, 0);

        return textifier.getText().stream()
                .map(it -> {
                    if (it instanceof List<?> list) {
                        return list.stream()
                                .map(Object::toString)
                                .collect(Collectors.joining());
                    }
                    return it.toString();
                })
                .collect(Collectors.joining());
    }
}
