package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Overloads van een ad-hoc extern krijgen een deterministische
 * {@code name$suffix}-symboolnaam in plaats van LLVM's volgnummer-unieking
 * ({@code name.694}).
 *
 * <p>De volgnummers schuiven zodra er ook maar één symbol eerder of later
 * bijkomt in de module: de undefined-symbolen die de runtime moet definiëren
 * lopen dan weg bij iedere herbouw. De suffix komt uit dezelfde
 * parameter-keys als de overload-sleutel ({@link FnKeys#stableSymbol}), dus
 * declaratie en aanroep delen één stabiel symbool.
 */
class StableOverloadSymbolTest {

    @TempDir
    Path tempDir;

    /**
     * Een symbool zonder definitie blijft een {@code declare}, of krijgt (als
     * JDK-lid, {@code pkg.Owner_member}) een {@code nabu_unresolved}-stub als
     * {@code define}; voor deze test telt alleen de naam.
     */
    private static final java.util.function.BiFunction<String, String, Boolean> DECLARED_OR_STUBBED =
            (signature, ll) -> ll.contains("declare " + signature) || ll.contains("define " + signature);

    /** LLVM's volgnummer-unieking: {@code @naam.123}. */
    private static final Pattern LLVM_SEQ_SUFFIX =
            Pattern.compile("@lib\\.Util_foo\\.\\d");

    /**
     * Twee statische overloads onder één IR-naam: de eerste houdt de kale
     * naam, de tweede krijgt een suffix uit de parametertype's — nooit een
     * volgnummer.
     */
    @Test
    void overloadsGetStableSymbolsInsteadOfSequenceNumbers() throws Exception {
        IRBuilder b = new IRBuilder("test/Over");
        b.beginFunction("Over_run", IRType.I32, List.of(), 0L);
        var noArgs = b.emitCall(CallKind.STATIC, "lib.Util_foo", IRType.I32,
                List.of(), List.of());
        b.emitCall(CallKind.STATIC, "lib.Util_foo", IRType.I32,
                List.of(IRType.I32), List.of(IRValue.ofI32(7)));
        b.emitReturn(noArgs);
        b.endFunction();

        String ll = compile(b.build());

        assertTrue(DECLARED_OR_STUBBED.apply("i32 @lib.Util_foo(", ll),
                "De eerste overload houdt de kale naam:\n" + declares(ll));
        assertTrue(ll.contains("lib.Util_foo$I32"),
                "De tweede overload krijgt een deterministische suffix uit de "
                        + "parametertype's:\n" + declares(ll));
        assertFalse(LLVM_SEQ_SUFFIX.matcher(ll).find(),
                "Geen LLVM-volgnummers meer in symbolen:\n" + declares(ll));
    }

    /**
     * Hetzelfde werkt voor overloade constructors: die gaan niet door fase
     * 2.5 maar door {@code declareExternalConstructor} tijdens de
     * body-emissie — dezelfde stabiele-symbool-route.
     */
    @Test
    void constructorOverloadsGetStableSymbols() throws Exception {
        IRType utilType = new IRType.Ptr(IRType.I8, "Llib/Util;");
        IRBuilder b = new IRBuilder("test/New");
        b.beginFunction("New_run", IRType.I32, List.of(), 0L);
        b.emitHeapAlloc("o1", utilType);
        b.emitCall(CallKind.SPECIAL, "lib.Util_init", IRType.VOID,
                List.of(), List.of());
        b.emitHeapAlloc("o2", utilType);
        b.emitCall(CallKind.SPECIAL, "lib.Util_init", IRType.VOID,
                List.of(IRType.I32), List.of(IRValue.ofI32(5)));
        b.emitReturn(IRValue.ofI32(0));
        b.endFunction();

        String ll = compile(b.build());

        assertTrue(DECLARED_OR_STUBBED.apply("void @lib.Util_init(", ll),
                "De eerste ctor houdt de kale naam:\n" + declares(ll));
        assertTrue(ll.contains("lib.Util_init$"),
                "De bezette ctor-naam krijgt een suffix:\n" + declares(ll));
        assertFalse(LLVM_SEQ_SUFFIX.matcher(ll).find(),
                "Ctor-overloads eveneens zonder volgnummer:\n" + declares(ll));
    }

    private String compile(IRModule module) throws Exception {
        Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(List.of(module), options(), out);
        Path ll = tempDir.resolve("out.ll");
        assertTrue(ll.toFile().exists(), ".ll-bestand niet aangemaakt");
        return Files.readString(ll);
    }

    private CompileOptions options() {
        return new CompileOptions(CompileOptions.OptLevel.NONE, false, null,
                CompileOptions.GcStrategy.BOEHM,
                io.github.potjerodekool.nabu.tools.JavaVersion.MINIMAL_VERSION);
    }

    /** Alle declare-/call-regels die naar lib.Util_ verwijzen (diagnose). */
    private static String declares(String ll) {
        StringBuilder out = new StringBuilder();
        for (String line : ll.split("\n")) {
            if (line.contains("lib.Util_")) {
                out.append(line.trim()).append('\n');
            }
        }
        return out.toString();
    }
}
