package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.backend.Backend;
import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.jvm.SlotAllocator;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.util.CompileException;
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

/**
 * Nieuwe slot-gebaseerde ASM-backend.
 *
 * Verschilt van de oude {@code asm}-backend doordat alle SSA-waarden via
 * JVM-local-variable-slots worden geadresseerd (zie {@link SlotAllocator}),
 * zodat SSA/phi-getransformeerde namen correct worden afgehandeld.
 */
public class AsmBackend implements Backend {

    @Override
    public void compile(final IRModule module,
                        final CompileOptions opts,
                        final Path output) throws CompileException {
        try {
            Files.createDirectories(output);
        } catch (IOException e) {
            throw new CompileException("Failed to create directory", e);
        }

        final var outputFile = output.resolve(module.name.replace('.', File.separatorChar) + ".class");
        final var parentDir = outputFile.getParent();

        if (!Files.exists(parentDir)) {
            try {
                Files.createDirectories(parentDir);
            } catch (IOException e) {
                throw new CompileException("Failed to create directory", e);
            }
        }

        final var emitter = new AsmByteCodeEmitter();
        emitter.emit(module, opts);
        final var bytecode = emitter.getBytecode();

        try {
            validate(bytecode);
            Files.write(outputFile, bytecode);
        } catch (IOException e) {
            throw new CompileException("Error while writing bytecode.", e);
        } catch (final Exception e) {
            throw new RuntimeException("Invalid bytecode generated: " + e.getMessage());
        }
    }

    public static void validate(final byte[] bytecode) {
        final String[] currentClass = {null};
        final String[] currentMethod = {null};
        try {
            final var classReader = new ClassReader(bytecode);
            final var classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS);
            final var classValidator = new CheckClassAdapter(classWriter, true);
            classReader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, classValidator) {
                @Override
                public void visit(final int version, final int access, final String name,
                                  final String signature, final String superName,
                                  final String[] interfaces) {
                    currentClass[0] = name;
                    super.visit(version, access, name, signature, superName, interfaces);
                }

                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(final int access, final String name,
                                                                   final String descriptor, final String signature,
                                                                   final String[] exceptions) {
                    currentMethod[0] = name + descriptor;
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }
            }, 0);
        } catch (Exception e) {
            throw new RuntimeException("[FRAME-DEBUG] class=" + currentClass[0]
                    + " method=" + currentMethod[0] + " :: " + e.getMessage(), e);
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
