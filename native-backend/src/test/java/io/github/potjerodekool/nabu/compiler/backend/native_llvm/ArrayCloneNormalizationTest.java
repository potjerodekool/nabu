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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Array-clone met eigenaar-owner wordt naar het generieke
 * {@code java.lang.Object_clone}-symbool herleid ({@link CallTargets}).
 *
 * <p>De frontend schrijft de gegenereerde enum-{@code values()}-methode als
 * {@code $VALUES.clone()} met de enum-klasse als owner — dus
 * {@code picocli.CommandLine$ScopeType_clone}. Die owner-keuze is bewust
 * (JVM-verifier, JVMS §6.5), maar native bestaat er geen {@code enum_clone}:
 * het is een array-kopie die de runtime via {@code Object.clone} afhandelt.
 *
 * <p>Zonder normalisatie maakt fase 2.5 per enum een ad-hoc extern aan
 * ({@code declare ...@picocli.CommandLine$ScopeType_clone}) dat nooit wordt
 * gedefinieerd — tien undefined references bij het linken.
 */
class ArrayCloneNormalizationTest {

    @TempDir
    Path tempDir;

    private static final IRType OBJ_TYPE   = new IRType.Ptr(IRType.I8, "Ljava/lang/Object;");
    private static final IRType ARRAY_TYPE = new IRType.Ptr(IRType.I8, "[Ltest/Color;");
    private static final IRType LIST_TYPE  = new IRType.Ptr(IRType.I8, "Ljava/util/Vector;");

    /**
     * {@code Color.values()} roept {@code $VALUES.clone()} aan met de
     * enum-klasse als owner en een array-receiver: precies de
     * {@code EnumCodeGenerator}-vorm.
     */
    @Test
    void arrayCloneWithOwnerBecomesObjectClone() throws Exception {
        IRBuilder b = new IRBuilder("test/Color");
        b.superType(OBJ_TYPE);
        // Frontend-conventie: paramTypes = expliciete params (leeg voor
        // clone()), args = [receiver].
        var values = new IRValue.Temp("VALUES", ARRAY_TYPE);
        b.beginFunction("Color_values", ARRAY_TYPE, List.of(values), 0L);
        var cloned = b.emitCall(CallKind.VIRTUAL, "test/Color_clone", ARRAY_TYPE,
                List.of(), List.of(values));
        b.emitReturn(cloned);
        b.endFunction();

        String ll = compile(b.build());

        assertTrue(ll.contains("call") && ll.contains("@java.lang.Object_clone"),
                "De array-clone moet op het generieke Object_clone-symbool uitkomen:\n"
                        + calls(ll));
        assertFalse(ll.contains("Color_clone"),
                "De eigenaarsgebonden clone-naam mag niet in de module voorkomen "
                        + "(noch als declare, noch als call):\n" + calls(ll));
    }

    /**
     * Een clone met eigenaar maar <i>niet</i>-array receiver blijft onder de
     * eigen naam: {@code Vector.clone()} is een gewone methode die de
     * library-implementatie hoort te leveren.
     */
    @Test
    void nonArrayCloneKeepsItsOwnerName() throws Exception {
        IRBuilder b = new IRBuilder("test/Copy");
        var list = new IRValue.Temp("LIST", LIST_TYPE);
        b.beginFunction("Copy_count", IRType.I32, List.of(list), 0L);
        var cloned = b.emitCall(CallKind.VIRTUAL, "java.util.Vector_clone", LIST_TYPE,
                List.of(), List.of(list));
        b.emitReturn(b.emitCall(CallKind.VIRTUAL, "java.util.List_size",
                IRType.I32, List.of(), List.of(cloned)));
        b.endFunction();

        String ll = compile(b.build());

        assertTrue(ll.contains("java.util.Vector_clone"),
                "Niet-array clone moet onder de eigen naam blijven:\n" + calls(ll));
        assertFalse(ll.contains("@java.lang.Object_clone"),
                "Alleen array-clones worden herleid:\n" + calls(ll));
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

    /** Alle call-/declare-regels uit het .ll (diagnose bij gefaalde assert). */
    private static String calls(String ll) {
        StringBuilder out = new StringBuilder();
        for (String line : ll.split("\n")) {
            if (line.contains("_clone")) {
                out.append(line.trim()).append('\n');
            }
        }
        return out.toString();
    }
}
