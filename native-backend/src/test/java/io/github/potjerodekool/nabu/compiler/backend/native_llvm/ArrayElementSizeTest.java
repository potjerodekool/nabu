package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Elke array draagt zijn elementgrootte in de header (i32 op offset 4).
 *
 * <p>De runtime kent het elementtype van een array niet; {@code System.arraycopy}
 * kopieerde daarom altijd 8 bytes per element. Een kopie van een {@code byte[]}
 * (de buffer van String/StringBuilder uit JDK-bytecode) schreef zo tot acht keer
 * voorbij het einde van de doel-array; in de picocli-demo kwam daardoor een
 * String-itable-pointer op de kop van een GC-freelist en crashte de volgende
 * allocatie.
 */
class ArrayElementSizeTest {

    @TempDir
    Path tempDir;

    @Test
    void arrayAllocationStoresElementSize() throws Exception {
        final IRBuilder b = new IRBuilder("pkg.Arrays");
        b.beginFunction("Arrays_make", IRType.VOID, List.of(), 0L);
        b.emitAllocaArray(3, IRType.I8);
        b.emitAllocaArray(3, IRType.I16);
        b.emitAllocaArray(3, IRType.I32);
        b.emitAllocaArray(3, new IRType.Ptr(IRType.I8, "Ljava/lang/String;"));
        b.emitReturn(null);
        b.endFunction();

        final Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(List.of(b.build()), new CompileOptions(
                CompileOptions.OptLevel.NONE, false, null,
                CompileOptions.GcStrategy.BOEHM,
                io.github.potjerodekool.nabu.tools.JavaVersion.MINIMAL_VERSION), out);
        final String ll = Files.readString(tempDir.resolve("out.ll"));

        // The low 8 bits are the element size, the bits above it the index
        // of the array type in nabu_array_type_names (for getClass()).
        final var stored = new java.util.ArrayList<Integer>();
        final var matcher = Pattern.compile("store i32 (\\d+), ptr %\\.esize\\.ptr").matcher(ll);
        while (matcher.find()) {
            stored.add(Integer.parseInt(matcher.group(1)));
        }
        assertTrue(stored.stream().map(v -> v & 0xff).toList().containsAll(List.of(1, 2, 4, 8)),
                "Verwacht elementgroottes 1, 2, 4 en 8 in de array-header: " + stored + "\n" + ll);
        assertTrue(stored.stream().allMatch(v -> (v >> 8) > 0),
                "Verwacht een array-type-index bij elke allocatie: " + stored);
        assertTrue(ll.contains("[Ljava/lang/String;") && ll.contains("@nabu_array_type_names"),
                "Verwacht de array-typenamen-tabel:\n" + ll);
    }
}
