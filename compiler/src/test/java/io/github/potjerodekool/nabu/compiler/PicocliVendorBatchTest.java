package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ferrase 0.4-volgorde: de vendored picocli-bron (real picocli 4.7.6) wordt
 * in één batch gecompileerd samen met de greet-app, ASM-backend.
 */
class PicocliVendorBatchTest {

    private File vendorRoot() {
        // cli-demo vendor-bron
        var current = new File(".").getAbsoluteFile();
        while (current != null && (!current.isDirectory() || !"nabu".equals(current.getName()))) {
            current = current.getParentFile();
        }
        final File demoVendor = new File("C:\\projects\\incurbation\\cli-demo\\vendor\\picocli-src");
        return demoVendor;
    }

    @Test
    void picocliGreetBatchCompiles() {
        final var vendor = vendorRoot();
        final File vendorSource = vendor;

        final var greetNabu = Path.of("C:\\projects\\incurbation\\cli-demo\\src\\main\\nabu");
        final var outDir = Path.of("C:\\projects\\incurbation\\cli-demo\\target\\probe-out");

        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, "C:\\projects\\nabu\\compiler\\src\\test\\resources")
                .option(CompilerOption.SOURCE_PATH,
                        greetNabu + File.pathSeparator + vendorSource)
                .option(CompilerOption.CLASS_OUTPUT, outDir.toString())
                .option(CompilerOption.SOURCE_OUTPUT, "C:\\projects\\incurbation\\cli-demo\\target\\probe-gen")
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false")
                .build();

        final var compiler = new NabuCompiler();
        final var diagnostics = new AtomicInteger();
        final var messages = new java.util.ArrayList<String>();
        compiler.setListener(diagnostic -> {
            if (diagnostic.getKind().name().equals("ERROR")) {
                diagnostics.incrementAndGet();
                messages.add(diagnostic.getMessage(java.util.Locale.ROOT).toString());
            } else if (diagnostic.getMessage(java.util.Locale.ROOT).toString().startsWith("[")
                    == false) {
                // NIET probe-ruis
            }
        });

        final var result = compiler.compile(options);

        final var found = Files.exists(outDir.resolve("picocli/CommandLine.class"));
        assertTrue(result == 0 && diagnostics.get() == 0,
                "Compile RC=" + result + " errors=" + diagnostics.get()
                        + "\n" + String.join("\n", messages)
                        + " CommandLine.class=" + found);
    }
}
