package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fase 2 (reflectie-metadata-emissie): een klasse met een reflect-config-entry
 * én IR-annotaties compileert door de volledige LLVM-backend; de .ll moet de
 * nabu_reflection_info-globals, veldoffsets, annotatie-entries en de
 * @llvm.global_ctors-registratie bevatten.
 */
class ReflectionMetadataEmissionTest {

    private static final String GREET_REFLECT_CONFIG = """
            [
              {
                "name" : "greet.GreetCommand",
                "allDeclaredConstructors" : true,
                "allPublicMethods" : true,
                "fields" : [
                  { "name" : "count" },
                  { "name" : "name" }
                ]
              }
            ]
            """;

    @TempDir
    Path tempDir;

    @Test
    void emitsReflectionInfoGlobalWithFieldsAndAnnotations() throws Exception {
        final var genDir = tempDir.resolve("META-INF").resolve("native-image")
                .resolve("picocli-generated");
        Files.createDirectories(genDir);
        Files.writeString(genDir.resolve("reflect-config.json"), GREET_REFLECT_CONFIG);

        final IRModule module = TestReflectionModel.greetCommandModule();

        final Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compile(List.of(module), CompileOptions.debug(), out);

        final String ll = Files.readString(tempDir.resolve("out.ll"));

        // registratiefunctie en global-ctors
        assertTrue(ll.contains("nabu_register_reflection"));
        assertTrue(ll.contains("@llvm.global_ctors"));
        assertTrue(ll.contains("nabu_reflect_init_greet_GreetCommand"));
        assertTrue(ll.contains("_ZNabugreet/GreetCommandEreflection_info"));

        // velden (C-string literal vorm in de .ll: c"count\00")
        assertTrue(ll.contains("c\"count\\00\""), "veldnaam count ontbreekt");
        assertTrue(ll.contains("c\"name\\00\""), "veldnaam name ontbreekt");

        // annotatie: type + ge-renderde array van names
        assertTrue(ll.contains("picocli/CommandLine$Option"));
        assertTrue(ll.contains("-n,--name"));
    }
}