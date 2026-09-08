package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import static org.junit.jupiter.api.Assertions.*;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.tools.JavaVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

/**
 * Fase 0: objectmodel (object-header, veldtoegang via GEP) en arrays
 * (ArrayStore + consistente array-header) voor de native LLVM-backend.
 */
class ObjectModelBackendTest {

    @TempDir
    Path tempDir;

    private static final IRType BOX_TYPE = new IRType.Ptr(IRType.I8, "Ltest/Box;");

    private void compile(IRModule module, CompileOptions opts) throws Exception {
        Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(module, opts, out);
        assertTrue(out.toFile().exists(), "Object file niet aangemaakt");
        Path ll = tempDir.resolve("out.ll");
        assertTrue(ll.toFile().exists(), ".ll bestand niet aangemaakt");
        assertTrue(ll.toFile().length() > 0, ".ll bestand is leeg");
    }

    private CompileOptions boehm() {
        return new CompileOptions(CompileOptions.OptLevel.NONE, false, null,
                CompileOptions.GcStrategy.BOEHM, JavaVersion.MINIMAL_VERSION);
    }

    // -------------------------------------------------------
    // Object-veldtoegang
    // -------------------------------------------------------

    @Test
    void objectFieldStoreAndLoad() throws Exception {
        IRBuilder b = new IRBuilder("test/Box");
        b.beginFunction("main", IRType.I32, List.of(), false);

        var obj = b.emitHeapAlloc("box", BOX_TYPE);
        var named = new IRValue.Named("x", IRType.I32, BOX_TYPE, false, 0);
        var field = new IRValue.Values(obj, named);

        b.emitStore(field, b.constInt(42));
        var val = b.emitLoad(field);
        b.emitReturn(val);
        b.endFunction();

        IRModule m = b.build();
        m.emitField(IRField.field(Flags.PUBLIC, "x", IRType.I32, null));
        compile(m, CompileOptions.defaults());
    }

    @Test
    void objectFieldStoreAndLoadBoehm() throws Exception {
        IRBuilder b = new IRBuilder("test/Box");
        b.beginFunction("main", IRType.I32, List.of(), false);

        var obj = b.emitHeapAlloc("box", BOX_TYPE);
        var named = new IRValue.Named("x", IRType.I32, BOX_TYPE, false, 0);
        var field = new IRValue.Values(obj, named);

        b.emitStore(field, b.constInt(7));
        var val = b.emitLoad(field);
        b.emitReturn(val);
        b.endFunction();

        IRModule m = b.build();
        m.emitField(IRField.field(Flags.PUBLIC, "x", IRType.I32, null));
        compile(m, boehm());
    }

    @Test
    void twoFieldsDifferentSlots() throws Exception {
        IRBuilder b = new IRBuilder("test/Pair");
        var pairType = new IRType.Ptr(IRType.I8, "Ltest/Pair;");
        b.beginFunction("main", IRType.I32, List.of(), false);

        var obj = b.emitHeapAlloc("pair", pairType);
        var a = new IRValue.Named("a", IRType.I32, pairType, false, 0);
        var fieldA = new IRValue.Values(obj, a);
        b.emitStore(fieldA, b.constInt(11));

        var bNamed = new IRValue.Named("b", IRType.I32, pairType, false, 1);
        var fieldB = new IRValue.Values(obj, bNamed);
        b.emitStore(fieldB, b.constInt(22));

        var loadedB = b.emitLoad(fieldB);
        b.emitReturn(loadedB);
        b.endFunction();

        IRModule m = b.build();
        m.emitField(IRField.field(Flags.PUBLIC, "a", IRType.I32, null));
        m.emitField(IRField.field(Flags.PUBLIC, "b", IRType.I32, null));
        compile(m, CompileOptions.defaults());
    }

    // -------------------------------------------------------
    // Arrays (header + ArrayStore/ArrayLoad/ArrayLength)
    // -------------------------------------------------------

    @Test
    void arrayStoreLoadAndLength() throws Exception {
        IRBuilder b = new IRBuilder("array/Test");
        b.beginFunction("main", IRType.I32, List.of(), false);

        // int[] a = new int[4]
        var arr = b.emitAllocaArray(4, IRType.I32);

        // a[2] = 99
        b.emitArrayStore(arr, b.constInt(2), b.constInt(99), IRType.I32);

        // len = a.length; a[2] + len
        var len = b.emitArrayLength(arr);
        var elem = b.emitArrayLoad(arr, b.constInt(2), IRType.I32);
        var sum = b.emitBinaryOp(io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction.BinaryOp.Op.ADD,
                elem, len);
        b.emitReturn(sum);
        b.endFunction();

        compile(b.build(), CompileOptions.defaults());
    }

    @Test
    void arrayStoreLoadAndLengthBoehm() throws Exception {
        IRBuilder b = new IRBuilder("array/Test");
        b.beginFunction("main", IRType.I32, List.of(), false);

        var arr = b.emitAllocaArray(5, IRType.I64);
        b.emitArrayStore(arr, b.constInt(0), b.constInt(100), IRType.I64);
        var len = b.emitArrayLength(arr);
        b.emitReturn(len);
        b.endFunction();

        compile(b.build(), boehm());
    }
}
