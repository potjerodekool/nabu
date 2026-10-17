package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Class-literals en library-objecten achter een Object-referentie.
 *
 * <p>Picocli's {@code CommandSpec.updateModelTransformer} geeft
 * {@code NoOpModelTransformer.class} aan {@code isNonDefault(Object, Object)},
 * dat er {@code equals} virtueel op aanroept. De class-literal was een kale
 * C-string ({@code "picocli/CommandLine$NoOpModelTransformer"}), dus de
 * vtable-load las de tekst "CommandL" als functiepointer en het programma
 * crashte bij opstart. Een class-literal is nu het runtime-Class-object
 * ({@code nabu_class_literal}), en library-objecten krijgen de vtable van
 * java.lang.Object (via een vroege module-constructor).
 */
class ClassLiteralObjectTest {

    @TempDir
    Path tempDir;

    private static final String OBJECT = "Ljava/lang/Object;";

    private String compileAndReadLl(final List<IRModule> modules) throws Exception {
        final Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(modules, new CompileOptions(
                CompileOptions.OptLevel.NONE, false, null,
                CompileOptions.GcStrategy.BOEHM,
                io.github.potjerodekool.nabu.tools.JavaVersion.MINIMAL_VERSION), out);
        return Files.readString(tempDir.resolve("out.ll"));
    }

    /** java.lang.Object met een virtuele {@code equals}, plus een klasse die een class-literal oplevert. */
    private List<IRModule> modules() {
        final IRType objectType = new IRType.Ptr(IRType.I8, OBJECT);
        final IRBuilder object = new IRBuilder("java.lang.Object");
        object.beginFunction("Object_equals", IRType.BOOL, List.of(
                new IRValue.Temp("this", objectType), new IRValue.Temp("o", objectType)), 0L);
        object.emitReturn(new IRValue.ConstBool(false));
        object.endFunction();

        final IRBuilder user = new IRBuilder("pkg.User");
        user.superType(objectType);
        user.beginFunction("User_cls", objectType, List.of(
                new IRValue.Temp("this", new IRType.Ptr(IRType.I8, "Lpkg/User;"))), 0L);
        user.emitReturn(new IRValue.ConstClass(new IRType.Ptr(IRType.I8, "Lpkg/Outer$Nested;")));
        user.endFunction();

        return List.of(object.build(), user.build());
    }

    @Test
    void classLiteralIsRuntimeClassObjectNotRawName() throws Exception {
        final String ll = compileAndReadLl(modules());

        assertTrue(Pattern.compile("call ptr @nabu_class_literal\\(ptr @[^)]+\\)").matcher(ll).find(),
                "Een class-literal moet via nabu_class_literal een Class-object worden:\n" + ll);
        assertTrue(ll.contains("c\"pkg/Outer$Nested\\00\""),
                "nabu_class_literal krijgt de interne naam:\n" + ll);
        assertFalse(Pattern.compile("ret ptr @\\.str").matcher(ll).find(),
                "De kale naam-string mag niet als object terugkomen:\n" + ll);
    }

    @Test
    void objectVtableIsRegisteredBeforeOrdinaryConstructors() throws Exception {
        final String ll = compileAndReadLl(modules());

        assertTrue(ll.contains("call void @nabu_set_object_vtable(ptr @_ZNabujava_lang_ObjectEvtable)"),
                "De Object-vtable moet aan de runtime worden doorgegeven:\n" + ll);
        assertTrue(ll.contains("{ i32 101, ptr @nabu_object_vtable_init, ptr null }"),
                "De registratie moet vóór de gewone (65535) constructors draaien:\n" + ll);
    }

    /**
     * Class.getSuperclass() zoekt de klasse met de genoemde naam op; daarvoor
     * registreert de backend de type-info van elke gecompileerde klasse. Zonder
     * die tabel las de runtime de super van het Class-object zelf
     * (java/lang/Class -> Object), en liep picocli's hiërarchie-lus eindeloos.
     */
    @Test
    void compiledTypeInfosAreRegisteredForLookupByName() throws Exception {
        final IRType classType = new IRType.Ptr(IRType.I8, "Ljava/lang/Class;");
        final IRBuilder walker = new IRBuilder("pkg.Walker");
        walker.beginFunction("Walker_up", classType, List.of(new IRValue.Temp("c", classType)), Flags.STATIC);
        final IRValue up = walker.emitCall(CallKind.VIRTUAL, "java.lang.Class_getSuperclass", classType,
                List.of(), List.of(new IRValue.Temp("c", classType)));
        walker.emitReturn(up);
        walker.endFunction();
        final var modules = new java.util.ArrayList<>(modules());
        modules.add(walker.build());

        assertFalse(compileAndReadLl(modules()).contains("@_ZNabuTypeInfoTable"),
                "Zonder getSuperclass-aanroep geen type-info-tabel");

        final String ll = compileAndReadLl(modules);

        assertTrue(Pattern.compile("@_ZNabuTypeInfoTable = internal constant \\[\\d+ x ptr\\] \\[[^\\n]*@_ZNabupkg_UserEtype_info")
                        .matcher(ll).find(),
                "De type-info-tabel moet pkg.User bevatten:\n" + ll);
        assertTrue(Pattern.compile("call void @nabu_register_type_infos\\(ptr @_ZNabuTypeInfoTable, i32 \\d+\\)")
                        .matcher(ll).find(),
                "De tabel moet aan de runtime worden doorgegeven:\n" + ll);
    }
}
