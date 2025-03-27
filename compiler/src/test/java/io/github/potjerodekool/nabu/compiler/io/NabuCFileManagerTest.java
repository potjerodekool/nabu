package io.github.potjerodekool.nabu.compiler.io;

import io.github.potjerodekool.nabu.compiler.CompilerOptions;
import io.github.potjerodekool.nabu.compiler.CompilerOption;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NabuCFileManagerTest {

    @Test
    void getFilesForLocation() {
        try (var fileManager = new NabuCFileManager()) {
            final var compilerOptions = new CompilerOptions.CompilerOptionsBuilder()
                    .option(CompilerOption.SOURCE_PATH, "src/main/java")
                    .build();

            fileManager.processOptions(compilerOptions);

            final var files = fileManager.getFilesForLocation(
                    StandardLocation.SOURCE_PATH,
                    FileObject.Kind.SOURCE_JAVA
            );
            assertFalse(files.isEmpty());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }
}