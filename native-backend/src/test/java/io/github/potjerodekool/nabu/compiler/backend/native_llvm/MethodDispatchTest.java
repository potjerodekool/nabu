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
import io.github.potjerodekool.nabu.tools.JavaVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Fase 1 (incrementeel): method dispatch in de native LLVM-backend.
 *
 * Hier: constructor-chaining — de {@code _init}-aanroep krijgt het
 * eerder gealloceerde HeapAlloc-object ('this') als eerste parameter
 * mee, zodat de aanroep overeenkomt met de gedeclareerde signatuur.
 */
class MethodDispatchTest {

    @TempDir
    Path tempDir;

    private static final IRType BOX_TYPE = new IRType.Ptr(IRType.I8, "Ltest/Box;");
    private static final IRType ANIMAL_TYPE = new IRType.Ptr(IRType.I8, "Ltest/Animal;");

    private String compileAndReadLl(IRModule module, CompileOptions opts) throws Exception {
        Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(module, opts, out);
        Path ll = tempDir.resolve("out.ll");
        assertTrue(ll.toFile().exists(), ".ll bestand niet aangemaakt");
        return Files.readString(ll);
    }

    private CompileOptions boehm() {
        return new CompileOptions(CompileOptions.OptLevel.NONE, false, null,
                CompileOptions.GcStrategy.BOEHM, JavaVersion.MINIMAL_VERSION);
    }

    // -------------------------------------------------------
    // Constructor-chaining
    // -------------------------------------------------------

    @Test
    void constructorCallPrependsThis() throws Exception {
        IRBuilder b = new IRBuilder("test/Box");

        // void Box_init(Box this, int val) { this.x = val; }
        var thisParam = new IRValue.Temp("this", BOX_TYPE);
        var valParam  = new IRValue.Temp("val", IRType.I32);
        b.beginFunction("test_Box_init", IRType.VOID, List.of(thisParam, valParam), 0L, true);
        var named = new IRValue.Named("x", IRType.I32, BOX_TYPE, false, 0);
        b.emitStore(new IRValue.Values(b.lookup("this"), named), b.lookup("val"));
        b.emitReturn(null);
        b.endFunction();

        // int main() { Box b = new Box(7); return 0; }
        b.beginFunction("main", IRType.I32, List.of(), false);
        b.emitHeapAlloc("box", BOX_TYPE);
        b.emitCall(CallKind.SPECIAL, "test_Box_init", IRType.VOID,
                List.of(IRType.I32), List.of(b.constInt(7)));
        b.emitReturn(b.constInt(0));
        b.endFunction();

        IRModule m = b.build();
        m.emitField(IRField.field(Flags.PUBLIC, "x", IRType.I32, null));

        String ll = compileAndReadLl(m, CompileOptions.defaults());

        // 'this' (een ptr) moet als eerste argument vooraan staan in de _init-call
        assertTrue(ll.contains("test_Box_init(ptr %"),
                "Verwacht: call met 'this' (ptr) als eerste arg, kreeg:\n" + ll);
        assertTrue(ll.contains("i32 7"),
                "Verwacht: de expliciete constructor-parameter (i32 7), kreeg:\n" + ll);
    }

    @Test
    void constructorCallPrependsThisBoehm() throws Exception {
        IRBuilder b = new IRBuilder("test/Box");

        var thisParam = new IRValue.Temp("this", BOX_TYPE);
        var valParam  = new IRValue.Temp("val", IRType.I32);
        b.beginFunction("test_Box_init", IRType.VOID, List.of(thisParam, valParam), 0L, true);
        var named = new IRValue.Named("x", IRType.I32, BOX_TYPE, false, 0);
        b.emitStore(new IRValue.Values(b.lookup("this"), named), b.lookup("val"));
        b.emitReturn(null);
        b.endFunction();

        b.beginFunction("main", IRType.I32, List.of(), false);
        b.emitHeapAlloc("box", BOX_TYPE);
        b.emitCall(CallKind.SPECIAL, "test_Box_init", IRType.VOID,
                List.of(IRType.I32), List.of(b.constInt(9)));
        b.emitReturn(b.constInt(0));
        b.endFunction();

        IRModule m = b.build();
        m.emitField(IRField.field(Flags.PUBLIC, "x", IRType.I32, null));

        String ll = compileAndReadLl(m, boehm());

        assertTrue(ll.contains("test_Box_init(ptr %"), ll);
    }

    // -------------------------------------------------------
    // Super-call (regressie: no-op zonder inheritance)
    // -------------------------------------------------------

    @Test
    void superCallRemainsNoOp() throws Exception {
        IRBuilder b = new IRBuilder("test/Sub");

        // void Sub_init(Sub this) { super(); }
        var thisParam = new IRValue.Temp("this",
                new IRType.Ptr(IRType.I8, "Ltest/Sub;"));
        b.beginFunction("test_Sub_init", IRType.VOID, List.of(thisParam), 0L, true);
        b.emitCall(CallKind.SPECIAL, "test_Super_super", IRType.VOID,
                List.of(), List.of(thisParam));
        b.emitReturn(null);
        b.endFunction();

        String ll = compileAndReadLl(b.build(), CompileOptions.defaults());

        // De super-call wordt niet naar een onbekende functie ge-emitteerd.
        assertFalse(ll.contains("test_Super_super"), ll);
    }

    // -------------------------------------------------------
    // Virtuele dispatch (vtable)
    // -------------------------------------------------------

    @Test
    void virtualDispatchUsesVtable() throws Exception {
        IRBuilder b = new IRBuilder("test/Animal");

        // void Animal_speak(Animal this) { }  (virtueel)
        var thisParam = new IRValue.Temp("this", ANIMAL_TYPE);
        b.beginFunction("test_Animal_speak", IRType.VOID, List.of(thisParam), 0L, false);
        b.emitReturn(null);
        b.endFunction();

        // int main() { Animal a = new Animal(); a.speak(); return 0; }
        b.beginFunction("main", IRType.I32, List.of(), false);
        IRValue alloc = b.emitHeapAlloc("a", ANIMAL_TYPE);
        // Receiver draagt het boxed-type (zoals de frontend voor een local van
        // het type Animal); de SSA-naam is de alloca, dus resolveValue vindt hem.
        IRValue receiver = new IRValue.Temp(IRValue.nameOf(alloc), ANIMAL_TYPE);
        b.emitCall(CallKind.VIRTUAL, "test_Animal_speak", IRType.VOID,
                List.of(), List.of(receiver));
        b.emitReturn(b.constInt(0));
        b.endFunction();

        String ll = compileAndReadLl(b.build(), CompileOptions.defaults());

        // De vtable-global voor test/Animal moet bestaan.
        assertTrue(ll.contains("Evtable"), ".ll mist de vtable-global:\n" + ll);
        // De header moet een vtable-pointer laden en de call moet indirect zijn
        // (load van functiepointer via de vtable), niet een directe call.
        assertTrue(ll.contains(".vtable"), ".ll mist vtable-load:\n" + ll);
        assertTrue(ll.contains(".fnptr"), ".ll mist indirecte call (functiepointer-load):\n" + ll);
        assertFalse(ll.contains("call void @test_Animal_speak("),
                "Verwacht GEEN directe call naar this-methode:\n" + ll);
    }

    // -------------------------------------------------------
    // Super-methode-aanroep (super.m() = directe call, invokespecial-gedrag)
    // -------------------------------------------------------

    @Test
    void superMethodCallIsDirectCall() throws Exception {
        IRBuilder b = new IRBuilder("test/Dog");

        // Externe super-methode: void Animal_speak(Animal this)
        var superThis = new IRValue.Temp("this", ANIMAL_TYPE);
        var superFn = b.beginFunction("test_Animal_speak", IRType.VOID,
                List.of(superThis), Flags.NATIVE, false);
        superFn.markExternal();
        b.endFunction();

        // void Dog_bark(Dog this) { super.speak(); }
        IRType dogType = new IRType.Ptr(IRType.I8, "Ltest/Dog;");
        var thisParam = new IRValue.Temp("this", dogType);
        b.beginFunction("test_Dog_bark", IRType.VOID, List.of(thisParam), 0L, false);
        // super.speak() -> CallKind.VIRTUAL naar de super-methode, receiver = this
        b.emitCall(CallKind.VIRTUAL, "test_Animal_speak", IRType.VOID,
                List.of(), List.of(thisParam));
        b.emitReturn(null);
        b.endFunction();

        String ll = compileAndReadLl(b.build(), CompileOptions.defaults());

        // Geen vtable-dispatch (externe super-methode staat NIET in Dog's vtable),
        // maar een directe call naar de super-implementatie.
        assertTrue(ll.contains("call void @test_Animal_speak("),
                "Verwacht een directe call naar de super-methode:\n" + ll);
        assertFalse(ll.contains(".fnptr"),
                "Super-call moet NIET via vtable/indirect dispatch gaan:\n" + ll);
    }

    // -------------------------------------------------------
    // Interface-call (itable)
    // -------------------------------------------------------

    @Test
    void interfaceCallUsesItable() throws Exception {
        IRBuilder b = new IRBuilder("test/Main");

        // Externe interface-methode: void Printable_print(Main this)
        IRType mainType = new IRType.Ptr(IRType.I8, "Ltest/Main;");
        var ifaceThis = new IRValue.Temp("this", mainType);
        var ifaceFn = b.beginFunction("test_Printable_print", IRType.VOID,
                List.of(ifaceThis), Flags.NATIVE, false);
        ifaceFn.markExternal();
        b.endFunction();

        // int main() { Main a = new Main(); ((Printable)a).print(); return 0; }
        b.beginFunction("main", IRType.I32, List.of(), false);
        IRValue alloc = b.emitHeapAlloc("a", mainType);
        IRValue receiver = new IRValue.Temp(IRValue.nameOf(alloc), mainType);
        b.emitCall(CallKind.INTERFACE, "test_Printable_print", IRType.VOID,
                List.of(), List.of(receiver));
        b.emitReturn(b.constInt(0));
        b.endFunction();

        IRModule m = b.build();
        m.interfaces(List.of(new IRType.Ptr(IRType.I8, "Ltest/Printable;")));

        String ll = compileAndReadLl(m, CompileOptions.defaults());

        // itable-global + itable-load + functiepointer-load; gÃ©Ã©n directe call.
        assertTrue(ll.contains("Eitable"), ".ll mist de itable-global:\n" + ll);
        assertTrue(ll.contains(".itable"), ".ll mist itable-load:\n" + ll);
        assertTrue(ll.contains(".ifnptr"), ".ll mist indirecte interface-call:\n" + ll);
        assertFalse(ll.contains("call void @test_Printable_print("),
                "Verwacht GEEN directe call naar de interface-methode:\n" + ll);
    }

    // -------------------------------------------------------
    // String-concat (Fase 2)
    // -------------------------------------------------------

    @Test
    void stringConcatCallsRuntimeHelper() throws Exception {
        IRBuilder b = new IRBuilder("test/Main");

        // void main() { String s = "Hello" + " World"; }
        b.beginFunction("main", IRType.VOID, List.of(), false);
        IRValue a = b.constString("Hello", "Ljava/lang/String;");
        IRValue bstr = b.constString(" World", "Ljava/lang/String;");
        b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, a, bstr);
        b.emitReturn(null);
        b.endFunction();

        String ll = compileAndReadLl(b.build(), CompileOptions.defaults());

        // Concat moet via de runtime-helper gaan (i.p.v. integer-add op pointers).
        assertTrue(ll.contains("@nabu_concat"),
                "Verwacht verwijzing naar @nabu_concat (runtime string-concat):\n" + ll);
        assertTrue(ll.contains(".str."), ".ll mist string-globals:\n" + ll);
    }
}
