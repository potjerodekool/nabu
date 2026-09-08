package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import static org.junit.jupiter.api.Assertions.*;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Object-model inheritance (Fase 5/wiring): super-velden, super-constructor
 * ketens en cross-class override-slot-deling in één batch-compilatie.
 *
 * Programma (i32-export):
 *   class Animal { int age; Animal(age) { this.age = age; }
 *                  int getAge() { return this.age; } }        // virtueel
 *   class Dog : Animal { int degree; Dog(age, degree)
 *                  { super(age); this.degree = degree; }
 *                  int getAge() { return this.age + this.degree; } }
 *   int main() { Animal a = new Dog(4, 5); return a.getAge(); }
 *
 * Verwachte exit-code: 9 (= 4 + 5). Bewijst in één keer:
 *  - de super-constructor-keten (Dog → Animal_init set age = 4)
 *  - het gelezen overgeërfde veld (age via Animal-layout in Dog-struct)
 *  - het eigen veld van Dog (degree)
 *  - de override-dispatch via een Animal-typverwijzing (Dog_getAge op het slot
 *    dat Animal_getAge deelt). Zonder slot-deling retourneert main 4; zonder
 *    super-keten is age ongedefinieerd (afwijkend van 9).
 */
class InheritanceE2eTest {

    @TempDir
    Path tempDir;

    private static final IRType ANIMAL_TYPE = new IRType.Ptr(IRType.I8, "Ltest/Animal;");
    private static final IRType DOG_TYPE    = new IRType.Ptr(IRType.I8, "Ltest/Dog;");
    private static final IRType EXC_TYPE    = new IRType.Ptr(IRType.I8, "Ljava/lang/Exception;");
    private static final IRType MYEXC_TYPE  = new IRType.Ptr(IRType.I8, "Ltest/MyException;");

    private IRValue fieldAccess(IRBuilder b, IRType owner, String name, IRType type, int fieldIndex) {
        return new IRValue.Values(b.lookup("this"), new IRValue.Named(name, type, owner, false, fieldIndex));
    }

    /** Bouwt Animal: veld age, ctor Animal_init(this, age), virtueel Animal_getAge(). */
    private IRModule animalModule() {
        IRBuilder b = new IRBuilder("test/Animal");
        b.field(IRField.field(Flags.PUBLIC, "age", IRType.I32, null));

        var thisParam = new IRValue.Temp("this", ANIMAL_TYPE);
        var ageParam  = new IRValue.Temp("age", IRType.I32);
        b.beginFunction("test_Animal_init", IRType.VOID, List.of(thisParam, ageParam), 0L, true);
        b.emitStore(fieldAccess(b, ANIMAL_TYPE, "age", IRType.I32, 0), b.lookup("age"));
        b.emitReturn(null);
        b.endFunction();

        b.beginFunction("test_Animal_getAge", IRType.I32, List.of(thisParam), 0L, false);
        b.emitReturn(b.emitLoad(fieldAccess(b, ANIMAL_TYPE, "age", IRType.I32, 0)));
        b.endFunction();

        return b.build();
    }

    /** Bouwt Dog : Animal — veld degree, ctor met super-keten, override getAge. */
    private IRModule dogModule() {
        IRBuilder b = new IRBuilder("test/Dog");
        b.superType(ANIMAL_TYPE);
        b.field(IRField.field(Flags.PUBLIC, "degree", IRType.I32, null));

        var thisParam = new IRValue.Temp("this", DOG_TYPE);
        var ageParam  = new IRValue.Temp("age", IRType.I32);
        var degParam  = new IRValue.Temp("degree", IRType.I32);
        b.beginFunction("test_Dog_init", IRType.VOID, List.of(thisParam, ageParam, degParam), 0L, true);
        // super(age) — args bevatten 'this' + de expliciete args (frontend-conventie)
        b.emitCall(CallKind.SPECIAL, "test_Animal_super", IRType.VOID,
                List.of(IRType.I32), List.of(thisParam, ageParam));
        b.emitStore(new IRValue.Values(thisParam, new IRValue.Named("degree", IRType.I32, DOG_TYPE, false, 0)),
                b.lookup("degree"));
        b.emitReturn(null);
        b.endFunction();

        b.beginFunction("test_Dog_getAge", IRType.I32, List.of(thisParam), 0L, false);
        IRValue age = b.emitLoad(
                new IRValue.Values(thisParam, new IRValue.Named("age", IRType.I32, ANIMAL_TYPE, false, 0)));
        IRValue deg = b.emitLoad(
                new IRValue.Values(thisParam, new IRValue.Named("degree", IRType.I32, DOG_TYPE, false, 0)));
        b.emitReturn(b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, age, deg));
        b.endFunction();

        return b.build();
    }

    /** Bouwt Main: main() { Animal a = new Dog(4,5); return a.getAge(); } */
    private IRModule mainModule() {
        IRBuilder b = new IRBuilder("test/Main");
        b.beginFunction("main", IRType.I32, List.of(), 0L, false);
        IRValue alloc = b.emitHeapAlloc("a", DOG_TYPE);
        b.emitCall(CallKind.SPECIAL, "test_Dog_init", IRType.VOID,
                List.of(IRType.I32, IRType.I32),
                List.of(b.constInt(4), b.constInt(5)));
        // Verwijzing met static type Animal → dispatch moet via Animal's vtable-slot
        IRValue receiver = new IRValue.Temp(IRValue.nameOf(alloc), ANIMAL_TYPE);
        IRValue result = b.emitCall(CallKind.VIRTUAL, "test_Animal_getAge", IRType.I32,
                List.of(), List.of(receiver));
        b.emitReturn(result);
        b.endFunction();
        return b.build();
    }

    @Test
    void inheritanceBatchDeeltKlassenInEenCompilatie() throws Exception {
        // Bewust omgekeerde volgorde: de backend moet de super-klasse
        // zelfstandig vóór de sub-klasse registreren.
        List<IRModule> modules = List.of(mainModule(), dogModule(), animalModule());

        Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileAll(modules, CompileOptions.forTarget("x86_64-w64-windows-gnu"), out);
        Path ll = tempDir.resolve("out.ll");
        assertTrue(Files.exists(ll), ".ll bestand niet aangemaakt");
        String text = Files.readString(ll);
        Files.copy(ll, Path.of("C:/Users/evert/AppData/Local/Temp/opencode/inheritance_batch.ll"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // 1. Super-constructor-keten: directe call naar Animal_init, geen restanten van de marker.
        assertTrue(text.contains("call void @test_Animal_init("),
                "Super-constructor moet als directe call worden geëmitteerd:\n" + text);
        assertFalse(text.contains("test_Animal_super"),
                "De _super-marker mag niet achterblijven in de IR:\n" + text);

        // 2. Type-info van beide klassen aanwezig.
        assertTrue(text.contains("test/Animal"), "Animal-type-info ontbreekt:\n" + text);
        assertTrue(text.contains("test/Dog"), "Dog-type-info ontbreekt:\n" + text);
    }

/**
 * Een batch-klasse die een library-klasse uitbreidt (java/lang/Exception zit
 * NIET in de batch): de backend moet synthetische type-info voor de super-keten
 * (Exception → Throwable → Object) emitteren, zodat nabu_instanceof /
 * nabu_can_catch over de super_type-keten matchen — cruciaal voor `catch (e : Exception)`
 * op een zelfgegooid subtype.
 */
@Test
void librarySuperTypeInfoSynthesisWiresExceptionChain() throws Exception {
    IRBuilder b = new IRBuilder("test/MyException");
    b.superType(EXC_TYPE);
    b.field(IRField.field(Flags.PUBLIC, "code", IRType.I32, null));

    var thisParam = new IRValue.Temp("this", MYEXC_TYPE);
    var codeParam = new IRValue.Temp("code", IRType.I32);
    b.beginFunction("test_MyException_init", IRType.VOID, List.of(thisParam, codeParam), 0L, true);
    b.emitCall(CallKind.SPECIAL, "java_lang_Exception_super", IRType.VOID,
            List.of(), List.of(thisParam));
    b.emitStore(new IRValue.Values(thisParam, new IRValue.Named("code", IRType.I32, MYEXC_TYPE, false, 0)),
            b.lookup("code"));
    b.emitReturn(null);
    b.endFunction();

    // main(): new MyException(7) + throw? Geen EH in deze .ll-assert — alleen type-info.
    IRBuilder main = new IRBuilder("test/Main");
    main.beginFunction("main", IRType.I32, List.of(), 0L, false);
    main.emitHeapAlloc("x", MYEXC_TYPE);
    main.emitCall(CallKind.SPECIAL, "test_MyException_init", IRType.VOID,
            List.of(IRType.I32), List.of(main.constInt(7)));
    main.emitReturn(main.constInt(0));
    main.endFunction();

    Path out = tempDir.resolve("libsuper.o");
    new NativeLLVMBackend().compileAll(
            List.of(main.build(), b.build()),
            CompileOptions.forTarget("x86_64-w64-windows-gnu"), out);
    Path ll = tempDir.resolve("libsuper.ll");
    String text = Files.readString(ll);
    Files.copy(ll, Path.of("C:/Users/evert/AppData/Local/Temp/opencode/libsuper.ll"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);

    // Keten-super-info: Exception → Throwable → Object, en de flag "java/lang/Exception"
    // moet als naam-constante bestaan (waar nabu_can_catch op matcht).
    assertTrue(text.contains("_ZNabutest/MyExceptionEtype_info"),
            "MyException-type-info ontbreekt:\n" + text);
    assertTrue(text.contains("_ZNabujava/lang/ExceptionEtype_info"), "Exception-type-info:\n" + text);
    assertTrue(text.contains("_ZNabujava/lang/ThrowableEtype_info"), "Throwable-type-info:\n" + text);
    assertTrue(text.contains("_ZNabujava/lang/ObjectEtype_info"), "Object-type-info:\n" + text);
    assertTrue(text.contains("java/lang/Exception"), "naam-constante java/lang/Exception:\n" + text);
}

/**
 * Volledige batch → native .exe → run. Geen exception-handling in dit
 * programma, dus geen EH-metadata: de exe-launch wordt (in tegenstelling
 * tot de try/catch-e2e) niet door de AV/delete-pending-pin geblokkeerd.
 */
    @Test
    void inheritanceEndToEndRunsAndReturnsOverriddenSum() throws Exception {
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) {
            root = root.getParentFile();
        }
        Path runtime = new File(root, "runtime/nabu_runtime.c").toPath();
        System.setProperty("nabu.runtime", runtime.toAbsolutePath().toString());

        List<IRModule> modules = List.of(mainModule(), dogModule(), animalModule());
        CompileOptions opts = CompileOptions.forTarget("x86_64-w64-windows-gnu");

        Path out = tempDir.resolve("inheritance.o");
        new NativeLLVMBackend().compileAll(modules, opts, out);

        Path exe = tempDir.resolve("inheritance.exe");
        assertTrue(Files.exists(exe), ".exe niet aangemaakt: " + exe);

        String extraBin = "C:\\programs\\mingw64\\mingw64\\bin";
        ProcessBuilder pb = new ProcessBuilder(exe.toString());
        pb.environment().compute("Path", (k, v) -> extraBin + ";" + (v == null ? "" : v));
        Process proc = pb.redirectErrorStream(true).start();
        String outStr = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        assertEquals(9, exit,
                "Expected exit 9 (overridden getAge = 4 + 5). Output:\n" + outStr);
    }
}