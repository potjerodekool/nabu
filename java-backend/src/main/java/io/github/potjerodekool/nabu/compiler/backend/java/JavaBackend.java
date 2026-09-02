package io.github.potjerodekool.nabu.compiler.backend.java;

import io.github.potjerodekool.nabu.backend.Backend;
import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.util.CompileException;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Backend die een {@link IRModule} naar JVM-bytecode compileert met behulp
 * van de Java 24 ClassFile API (java.lang.classfile).
 *
 * Geregistreerd in plugin.xml als backend "JAVA".
 */
public class JavaBackend implements Backend {

    public static final int MINIMUM_JAVA_FEATURE_VERSION = 24;

    public JavaBackend() {
        requireJava24OrLater();
    }

    static void requireJava24OrLater() {
        if (Runtime.version().feature() < MINIMUM_JAVA_FEATURE_VERSION) {
            throw new UnsupportedOperationException(
                    "JavaBackend requires Java " + MINIMUM_JAVA_FEATURE_VERSION
                            + " or later because it uses the ClassFile API, but the current runtime is Java "
                            + Runtime.version().feature());
        }
    }

    @Override
    public void compile(final IRModule module,
                        final CompileOptions opts,
                        final Path output) throws CompileException {
        final var emitter = new ClassFileByteCodeEmitter();
        final var classBytes = emitter.emit(module, opts);

        validate(classBytes);

        try {
            Files.createDirectories(output);
            final var outputFile = output.resolve(module.name.replace('.', File.separatorChar) + ".class");
            final var parentDir = outputFile.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }
            Files.write(outputFile, classBytes);
        } catch (IOException e) {
            throw new CompileException("Error while writing bytecode.", e);
        }
    }

    /**
     * Controleert dat de gegenereerde bytes een goed gevormd klassebestand
     * vormen door ze te parsen met de ClassFile API.
     */
    private static void validate(final byte[] classBytes) throws CompileException {
        try {
            final var model = ClassFile.of().parse(classBytes);
            if (model == null || model.thisClass() == null) {
                throw new CompileException("Invalid bytecode generated: missing this-class");
            }
        } catch (final RuntimeException e) {
            throw new CompileException("Invalid bytecode generated: " + e.getMessage(), e);
        }
    }
}
