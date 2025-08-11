package io.github.potjerodekool.nabu.compiler.io;

import io.github.potjerodekool.nabu.compiler.CompilerOptions;
import io.github.potjerodekool.nabu.compiler.CompilerOption;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class NabuCFileManagerTest {

    @Test
    void getFilesForLocation() {
        try (var fileManager = new NabuCFileManager()) {
            final var compilerOptions = new CompilerOptions.CompilerOptionsBuilder()
                    .option(CompilerOption.SOURCE_PATH,
                            "src/main/java" + File.pathSeparator + "src/main/nabu"
                    )
                    .build();

            fileManager.processOptions(compilerOptions);

            final var files = fileManager.list(
                    StandardLocation.SOURCE_PATH,
                    "io.github.potjerodekool.nabu.compiler.ast.element",
                    EnumSet.of(
                            FileObject.Kind.SOURCE_NABU,
                            FileObject.Kind.SOURCE_JAVA,
                            FileObject.Kind.CLASS
                    )
            );

            final var compilerContext = new CompilerContextImpl(
                    null,
                    fileManager
            );

            final var clazz = compilerContext.getClassElementLoader().loadClass(
                    null,
                    "AnnotationValueVisitor"
            );

            // io.github.potjerodekool.nabu.compiler.ast.element.AnnotationValueVisitor

            files.forEach(file -> {
                System.out.println(file.getFileName());
            });

            assertTrue(files.iterator().hasNext());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }
}