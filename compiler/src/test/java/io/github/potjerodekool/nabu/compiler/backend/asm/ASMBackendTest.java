package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.backend.CompileException;
import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.ir.IRBuilder;
import io.github.potjerodekool.nabu.ir.IRModule;
import io.github.potjerodekool.nabu.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Disabled
class ASMBackendTest {

    @TempDir
    Path tempDir;

    private IRBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new IRBuilder("test");
        builder.setLocation("test.lang", 1, 1);
    }

    private IRModule buildAndGet() {
        return builder.build();
    }

    @Test
    @Order(1)
    void mainFunction() throws Exception {
        final var paramType = new IRValue.Temp("args", new IRType.Array(
                new IRType.Ptr(new IRType.Ptr(IRType.I8)),
                0
        ));

        builder.beginFunction("main", IRType.VOID, List.of(paramType), false);
        builder.emitReturn(null);
        builder.endFunction();

        final var module = buildAndGet();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("main", String[].class);
            method.trySetAccessible();
            method.invoke(null, (Object) new String[]{});
        });
    }

    private void loadClass(final Path classFileName,
                           final IRModule module,
                           final ClassConsumer classConsumer) throws Exception {
        final var url = classFileName.getParent().toUri().toURL();

        try (final var loader = new URLClassLoader(
                new URL[]{url}, getClass().getClassLoader())) {
            final var clazz = loader.loadClass(module.name);
            classConsumer.accept(clazz);
        }
    }

    @Test @Order(4)
    void constDoubleReturn() throws Exception {
        builder.beginFunction("getPi", IRType.F64, List.of(), false);
        builder.emitReturn(builder.constFloat(3.14159));
        builder.endFunction();

        final var module = buildAndGet();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("getPi");
            method.trySetAccessible();
            final var result = (Double) method.invoke(null);
            assertEquals(3.14159, result);
        });
    }

    @Test @Order(5)
    void constFloat32Return() throws Exception {
        builder.beginFunction("getE", new IRType.Float(32), List.of(), false);
        builder.emitReturn(IRValue.ofF32(2.718f));
        builder.endFunction();

        final var module = buildAndGet();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("getE");
            method.trySetAccessible();
            final var result = (Float) method.invoke(null);
            assertEquals(2.718f, result);
        });
    }

    @Test @Order(6)
    void constBoolTrue() throws Exception {
        builder.beginFunction("isTrue", IRType.BOOL, List.of(), false);
        builder.emitReturn(builder.constBool(true));
        builder.endFunction();
        final var module = buildAndGet();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("isTrue");
            method.trySetAccessible();
            final var result = (Boolean) method.invoke(null);
            assertTrue(result);
        });
    }

    @Test @Order(7)
    void constBoolFalse() throws Exception {
        builder.beginFunction("isFalse", IRType.BOOL, List.of(), false);
        builder.emitReturn(builder.constBool(false));
        builder.endFunction();

        final var module = buildAndGet();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("isFalse");
            method.trySetAccessible();
            final var result = (Boolean) method.invoke(null);
            assertFalse(result);
        });
    }

    @Test @Order(8)
    void constNullPtr() throws Exception {
        builder.beginFunction("getNull", new IRType.Ptr(IRType.I32), List.of(), false);
        builder.emitReturn(IRValue.nullPtr(IRType.I32));
        builder.endFunction();

        final var module = buildAndGet();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("getNull");
            method.trySetAccessible();
            final var result = (Boolean) method.invoke(null);
            assertNull(result);
        });
    }

    @Test @Order(9)
    void constStringAsGlobal() throws Exception {
        builder.declareGlobal("greeting",
                new IRType.Ptr(IRType.I8), IRValue.ofString("Hallo wereld"));
        builder.beginFunction("main", IRType.VOID, List.of(), true);
        builder.endFunction();

        final var module = buildAndGet();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("main");
            method.trySetAccessible();
            method.invoke(null);
        });
    }

    @Test @Order(10)
    void constStringInlineViaPuts() throws Exception {
        var putsType = IRType.fn(IRType.I32, new IRType.Ptr(IRType.I8));
        builder.declareExternalFunction("puts", putsType);

        System.out.println("Hello, World!");

        builder.beginFunction("main", IRType.I32, List.of(), true);
        IRValue msg = builder.constString("Hello, World!");
        builder.emitCall("puts", IRType.I32, List.of(msg));
        builder.emitReturn(builder.constInt(0));
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    @Test @Order(11)
    void binaryOpAdd() throws Exception {
        builder.beginFunction("add", IRType.I32, List.of(), false);
        var result = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD,
                builder.constInt(3), builder.constInt(4));
        builder.emitReturn(result);
        builder.endFunction();

        final var module = buildAndGet();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("add");
            method.trySetAccessible();
            final var functionResult = (int) method.invoke(null);
            assertEquals(7, functionResult);
        });
    }

    @Test @Order(12)
    void binaryOpSubMulDiv() throws Exception {
        builder.beginFunction("calc", IRType.I32, List.of(), false);
        var a   = builder.constInt(10);
        var b   = builder.constInt(3);
        var sub = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.SUB, a, b);
        var mul = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.MUL, sub, b);
        var div = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.DIV, mul, b);
        builder.emitReturn(div);
        builder.endFunction();

        final var module = buildAndGet();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("calc");
            method.trySetAccessible();
            final var functionResult = (int) method.invoke(null);
            assertEquals(7, functionResult);
        });
    }

    @Test @Order(13)
    void binaryOpMod() throws Exception {
        builder.beginFunction("mod", IRType.I32, List.of(), false);
        var result = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.MOD,
                builder.constInt(10), builder.constInt(3));
        builder.emitReturn(result);
        builder.endFunction();

        final var module = buildAndGet();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("mod");
            method.trySetAccessible();
            final var functionResult = (int) method.invoke(null);
            assertEquals(1, functionResult);
        });
    }

    @Test @Order(14)
    void binaryOpComparisons() throws Exception {
        builder.beginFunction("cmp", IRType.BOOL, List.of(), false);
        var a  = builder.constInt(5);
        var b  = builder.constInt(10);
        var eq  = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.EQ,  a, b);
        var lt  = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.LT,  a, b);
        var gte = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.GTE, a, b);
        builder.emitReturn(lt); // return (a < b)
        builder.endFunction();

        final var module = buildAndGet();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("cmp");
            method.trySetAccessible();
            final var functionResult = (boolean) method.invoke(null);
            assertTrue(functionResult);
        });
    }

    @Test @Order(15)
    void allocaLoadStore() throws Exception {
        builder.beginFunction("main", IRType.I32, List.of(), true);
        var ptr = builder.emitAlloca("x", IRType.I32);
        builder.emitStore(ptr, builder.constInt(99));
        var val = builder.emitLoad(ptr);
        builder.emitReturn(val);
        builder.endFunction();

        final var module = buildAndGet();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("main");
            method.trySetAccessible();
            final var functionResult = (int) method.invoke(null);
            assertEquals(99, functionResult);
        });
    }

    @Test @Order(16)
    void internalFunctionCall() throws Exception {
        // fn helper() -> i32 { return 7; }
        builder.beginFunction("helper", IRType.I32, List.of(), false);
        builder.emitReturn(builder.constInt(7));
        builder.endFunction();

        // fn main() -> i32 { return helper(); }
        builder.beginFunction("main", IRType.I32, List.of(), true);
        var res = builder.emitCall("helper", IRType.I32, List.of());
        builder.emitReturn(res);
        builder.endFunction();

        final var module = buildAndGet();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("main");
            method.trySetAccessible();
            final var functionResult = (int) method.invoke(null);
            assertEquals(7, functionResult);
        });
    }

    @Test @Order(17)
    void externalFunctionCallPuts() throws Exception {
        var putsType = IRType.fn(IRType.I32, new IRType.Ptr(IRType.I8));
        builder.declareExternalFunction("puts", putsType);

        builder.beginFunction("main", IRType.I32, List.of(), true);
        var msg = builder.constString("Test extern");
        builder.emitCall("puts", IRType.I32, List.of(msg));
        builder.emitReturn(builder.constInt(0));
        builder.endFunction();

        final var module = buildAndGet();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("main");
            method.trySetAccessible();
            final var functionResult = (int) method.invoke(null);
            assertEquals(7, functionResult);
        });
    }


    private Path compileDefault(final IRModule module) throws CompileException {
        final var classFileName = module.name + ".class";
        Path out = tempDir.resolve(classFileName);
        new ASMBackend()
                .compile(module, CompileOptions.defaults(), out);

        return out;
    }

}

@FunctionalInterface
interface ClassConsumer {
    void accept(Class<?> clazz) throws Exception;
}