package io.github.potjerodekool.nabu.compiler.backend;

import static org.junit.jupiter.api.Assertions.*;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.compiler.backend.native_llvm.NativeLLVMBackend;
import io.github.potjerodekool.nabu.ir.IRBuilder;
import io.github.potjerodekool.nabu.ir.IRModule;
import io.github.potjerodekool.nabu.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import io.github.potjerodekool.nabu.ir.instructions.IRInstruction.BinaryOp.Op;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

/**
 * Unit tests per instructietype voor de NativeLLVMBackend.
 *
 * Elke test bouwt een minimale IRModule met de hand en
 * compileert die via de backend. Er wordt gecontroleerd
 * dat de compilatie zonder uitzondering verloopt en dat
 * het output-bestand wordt aangemaakt.
 *
 * Tests die LLVM nodig hebben vereisen de Bytedeco-dependency.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NativeLLVMBackendTest {

    @TempDir
    Path tempDir;

    private IRBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new IRBuilder("test");
        builder.setLocation("test.lang", 1, 1);
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private IRModule buildAndGet() {
        return builder.build();
    }

    private void compileDefault(IRModule module) throws Exception {
        Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(module,
                CompileOptions.defaults(), out);
        assertTrue(out.toFile().exists(), "Object file niet aangemaakt");
    }

    private void compileDebug(IRModule module) throws Exception {
        Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compile(module, CompileOptions.debug(), out);
        assertTrue(out.toFile().exists(), "Object file niet aangemaakt");
    }

    // -------------------------------------------------------
    // ConstInt
    // -------------------------------------------------------

    @Test @Order(1)
    void constInt32ReturnValue() throws Exception {
        builder.beginFunction("main", IRType.I32, List.of(), false);
        builder.emitReturn(builder.constInt(42));
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    @Test @Order(2)
    void constInt64ReturnValue() throws Exception {
        builder.beginFunction("main", IRType.I64, List.of(), false);
        builder.emitReturn(builder.constInt(1_000_000L, 64));
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    @Test @Order(3)
    void constIntNegative() throws Exception {
        builder.beginFunction("main", IRType.I32, List.of(), false);
        builder.emitReturn(builder.constInt(-1));
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // ConstFloat
    // -------------------------------------------------------

    @Test @Order(4)
    void constDoubleReturn() throws Exception {
        builder.beginFunction("getPi", IRType.F64, List.of(), false);
        builder.emitReturn(builder.constFloat(3.14159));
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    @Test @Order(5)
    void constFloat32Return() throws Exception {
        builder.beginFunction("getE", new IRType.Float(32), List.of(), false);
        builder.emitReturn(IRValue.ofF32(2.718f));
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // ConstBool
    // -------------------------------------------------------

    @Test @Order(6)
    void constBoolTrue() throws Exception {
        builder.beginFunction("isTrue", IRType.BOOL, List.of(), false);
        builder.emitReturn(builder.constBool(true));
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    @Test @Order(7)
    void constBoolFalse() throws Exception {
        builder.beginFunction("isFalse", IRType.BOOL, List.of(), false);
        builder.emitReturn(builder.constBool(false));
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // ConstNull
    // -------------------------------------------------------

    @Test @Order(8)
    void constNullPtr() throws Exception {
        builder.beginFunction("getNull", new IRType.Ptr(IRType.I32), List.of(), false);
        builder.emitReturn(IRValue.nullPtr(IRType.I32));
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // ConstString — als IRGlobal
    // -------------------------------------------------------

    @Test @Order(9)
    void constStringAsGlobal() throws Exception {
        builder.declareGlobal("greeting",
                new IRType.Ptr(IRType.I8), IRValue.ofString("Hallo wereld"));
        builder.beginFunction("main", IRType.VOID, List.of(), false);
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // ConstString — inline, via externe aanroep
    // -------------------------------------------------------

    @Test @Order(10)
    void constStringInlineViaPuts() throws Exception {
        var putsType = IRType.fn(IRType.I32, new IRType.Ptr(IRType.I8));
        builder.declareExternalFunction("puts", putsType);

        builder.beginFunction("main", IRType.I32, List.of(), false);
        IRValue msg = builder.constString("Hello, World!");
        builder.emitCall("puts", IRType.I32, List.of(msg));
        builder.emitReturn(builder.constInt(0));
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // BinaryOp — rekenkundig
    // -------------------------------------------------------

    @Test @Order(11)
    void binaryOpAdd() throws Exception {
        builder.beginFunction("add", IRType.I32, List.of(), false);
        var result = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD,
                builder.constInt(3), builder.constInt(4));
        builder.emitReturn(result);
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    @Test @Order(12)
    void binaryOpSubMulDiv() throws Exception {
        builder.beginFunction("calc", IRType.I32, List.of(), false);
        var a   = builder.constInt(10);
        var b   = builder.constInt(3);
        var sub = builder.emitBinaryOp(Op.SUB, a, b);
        var mul = builder.emitBinaryOp(Op.MUL, sub, b);
        var div = builder.emitBinaryOp(Op.DIV, mul, b);
        builder.emitReturn(div);
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    @Test @Order(13)
    void binaryOpMod() throws Exception {
        builder.beginFunction("mod", IRType.I32, List.of(), false);
        var result = builder.emitBinaryOp(Op.MOD,
                builder.constInt(10), builder.constInt(3));
        builder.emitReturn(result);
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // BinaryOp — vergelijkingen
    // -------------------------------------------------------

    @Test @Order(14)
    void binaryOpComparisons() throws Exception {
        builder.beginFunction("cmp", IRType.BOOL, List.of(), false);
        var a  = builder.constInt(5);
        var b  = builder.constInt(10);
        var eq  = builder.emitBinaryOp(Op.EQ,  a, b);
        var lt  = builder.emitBinaryOp(Op.LT,  a, b);
        var gte = builder.emitBinaryOp(Op.GTE, a, b);
        builder.emitReturn(lt); // return (a < b)
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // Alloca / Load / Store
    // -------------------------------------------------------

    @Test @Order(15)
    void allocaLoadStore() throws Exception {
        builder.beginFunction("main", IRType.I32, List.of(), false);
        var ptr = builder.emitAlloca("x", IRType.I32);
        builder.emitStore(ptr, builder.constInt(99));
        var val = builder.emitLoad(ptr);
        builder.emitReturn(val);
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // Interne functie-aanroep
    // -------------------------------------------------------

    @Test @Order(16)
    void internalFunctionCall() throws Exception {
        // fn helper() -> i32 { return 7; }
        builder.beginFunction("helper", IRType.I32, List.of(), false);
        builder.emitReturn(builder.constInt(7));
        builder.endFunction();

        // fn main() -> i32 { return helper(); }
        builder.beginFunction("main", IRType.I32, List.of(), false);
        var res = builder.emitCall("helper", IRType.I32, List.of());
        builder.emitReturn(res);
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // Externe functie-aanroep (puts)
    // -------------------------------------------------------

    @Test @Order(17)
    void externalFunctionCallPuts() throws Exception {
        var putsType = IRType.fn(IRType.I32, new IRType.Ptr(IRType.I8));
        builder.declareExternalFunction("puts", putsType);

        builder.beginFunction("main", IRType.I32, List.of(), false);
        var msg = builder.constString("Test extern");
        builder.emitCall("puts", IRType.I32, List.of(msg));
        builder.emitReturn(builder.constInt(0));
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // Indirecte aanroep via functiepointer
    // -------------------------------------------------------

    @Test @Order(18)
    void indirectFunctionCall() throws Exception {
        var addType = IRType.fn(IRType.I32, IRType.I32, IRType.I32);

        // fn add(a, b) { return a + b; }
        var pA = new IRValue.Temp("%a", IRType.I32);
        var pB = new IRValue.Temp("%b", IRType.I32);
        builder.beginFunction("add", IRType.I32, List.of(pA, pB), false);
        var sum = builder.emitBinaryOp(Op.ADD,
                builder.lookup("a"), builder.lookup("b"));
        builder.emitReturn(sum);
        builder.endFunction();

        // fn main() -> i32 { val f = add; return f(3, 4); }
        builder.beginFunction("main", IRType.I32, List.of(), false);
        var ref  = builder.functionRef("add", addType);
        var slot = builder.storeFunctionPointer("op", ref);
        var fp   = builder.emitLoad(slot);
        var res  = builder.emitIndirectCall(fp, addType,
                List.of(builder.constInt(3), builder.constInt(4)));
        builder.emitReturn(res);
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // Globals
    // -------------------------------------------------------

    @Test @Order(19)
    void globalVariableReadWrite() throws Exception {
        builder.declareGlobal("counter", IRType.I32, IRValue.ofI32(0));

        builder.beginFunction("main", IRType.I32, List.of(), false);
        var ptr = builder.lookup("counter");
        builder.emitStore(ptr, builder.constInt(42));
        var val = builder.emitLoad(ptr);
        builder.emitReturn(val);
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    @Test @Order(20)
    void globalConstant() throws Exception {
        builder.declareConstant("MAX", IRType.I32, IRValue.ofI32(100));

        builder.beginFunction("getMax", IRType.I32, List.of(), false);
        var ptr = builder.lookup("MAX");
        var val = builder.emitLoad(ptr);
        builder.emitReturn(val);
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // CondBranch (if/else)
    // -------------------------------------------------------

    @Test @Order(21)
    void condBranchIfElse() throws Exception {
        builder.beginFunction("abs", IRType.I32, List.of(
                new IRValue.Temp("%n", IRType.I32)),
                false
        );

        // Werk in het entry-blok
        var n     = builder.lookup("n");
        var zero  = builder.constInt(0);
        var isNeg = builder.emitBinaryOp(Op.LT, n, zero);

        // Maak doelblokken aan — beginBlock wisselt het huidige blok
        var ifBlk   = builder.currentBlock();
        var thenBlk = builder.beginBlock("then");
        var elseBlk = builder.beginBlock("else");

        // Terug naar het entry-blok voor de condBranch
        // entry-blok is de eerste IRBasicBlock van de functie
        builder.setCurrentBlock(ifBlk);         // terugzetten
        builder.emitCondBranch(isNeg, thenBlk, elseBlk);

        // then: return -n
        builder.setCurrentBlock(thenBlk);
        var neg = builder.emitBinaryOp(Op.SUB, zero, n);
        builder.emitReturn(neg);

        // else: return n
        builder.setCurrentBlock(elseBlk);
        builder.emitReturn(n);

        builder.endFunction();
        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // While-loop via basisblokken
    // -------------------------------------------------------

    @Test @Order(22)
    void whileLoopViaBlocks() throws Exception {
        builder.beginFunction("countDown", IRType.I32, List.of(), false);

        var iPtr = builder.emitAlloca("i", IRType.I32);
        builder.emitStore(iPtr, builder.constInt(10));

        // Sla entry-blok op vóór beginBlock de cursor verplaatst
        var entryBlk = builder.currentBlock();

        var condBlk = builder.beginBlock("while.cond");
        var bodyBlk = builder.beginBlock("while.body");
        var exitBlk = builder.beginBlock("while.exit");

        // Terug naar entry voor de sprong naar condBlk
        builder.setCurrentBlock(entryBlk);
        builder.emitBranch(condBlk);

        // Conditie: i > 0
        builder.setCurrentBlock(condBlk);
        var i    = builder.emitLoad(iPtr);
        var cond = builder.emitBinaryOp(Op.GT, i, builder.constInt(0));
        builder.emitCondBranch(cond, bodyBlk, exitBlk);

        // Body: i = i - 1
        builder.setCurrentBlock(bodyBlk);
        var iDec = builder.emitBinaryOp(Op.SUB, i, builder.constInt(1));
        builder.emitStore(iPtr, iDec);
        builder.emitBranch(condBlk);

        // Exit: return i
        builder.setCurrentBlock(exitBlk);
        var finalI = builder.emitLoad(iPtr);
        builder.emitReturn(finalI);

        builder.endFunction();
        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // For-loop via basisblokken
    // -------------------------------------------------------

    @Test @Order(23)
    void forLoopViaBlocks() throws Exception {
        builder.beginFunction("sumTo", IRType.I32, List.of(), false);

        var sumPtr = builder.emitAlloca("sum", IRType.I32);
        var iPtr   = builder.emitAlloca("i",   IRType.I32);
        builder.emitStore(sumPtr, builder.constInt(0));
        builder.emitStore(iPtr,   builder.constInt(1));

        var entryBlk = builder.currentBlock();
        var condBlk   = builder.beginBlock("for.cond");
        var bodyBlk   = builder.beginBlock("for.body");
        var updateBlk = builder.beginBlock("for.update");
        var exitBlk   = builder.beginBlock("for.exit");

        builder.setCurrentBlock(entryBlk);
        builder.emitBranch(condBlk);

        // Conditie: i <= 10
        builder.setCurrentBlock(condBlk);
        var i    = builder.emitLoad(iPtr);
        var cond = builder.emitBinaryOp(Op.LTE, i, builder.constInt(10));
        builder.emitCondBranch(cond, bodyBlk, exitBlk);

        // Body: sum += i
        builder.setCurrentBlock(bodyBlk);
        var sum    = builder.emitLoad(sumPtr);
        var newSum = builder.emitBinaryOp(Op.ADD, sum, i);
        builder.emitStore(sumPtr, newSum);
        builder.emitBranch(updateBlk);

        // Update: i++
        builder.setCurrentBlock(updateBlk);
        var iNew = builder.emitBinaryOp(Op.ADD, i, builder.constInt(1));
        builder.emitStore(iPtr, iNew);
        builder.emitBranch(condBlk);

        // Exit: return sum
        builder.setCurrentBlock(exitBlk);
        var result = builder.emitLoad(sumPtr);
        builder.emitReturn(result);

        builder.endFunction();
        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // Do-while via basisblokken
    // -------------------------------------------------------

    @Test @Order(24)
    void doWhileLoopViaBlocks() throws Exception {
        builder.beginFunction("doCount", IRType.I32, List.of(), false);

        var iPtr = builder.emitAlloca("i", IRType.I32);
        builder.emitStore(iPtr, builder.constInt(0));

        var entryBlk = builder.currentBlock();

        var bodyBlk = builder.beginBlock("dowhile.body");
        var condBlk = builder.beginBlock("dowhile.cond");
        var exitBlk = builder.beginBlock("dowhile.exit");

        builder.setCurrentBlock(entryBlk);
        builder.emitBranch(bodyBlk);

        // Body: i++
        builder.setCurrentBlock(bodyBlk);
        var i    = builder.emitLoad(iPtr);
        var iInc = builder.emitBinaryOp(Op.ADD, i, builder.constInt(1));
        builder.emitStore(iPtr, iInc);
        builder.emitBranch(condBlk);

        // Conditie: i < 5
        builder.setCurrentBlock(condBlk);
        var iCheck = builder.emitLoad(iPtr);
        var cond   = builder.emitBinaryOp(Op.LT, iCheck, builder.constInt(5));
        builder.emitCondBranch(cond, bodyBlk, exitBlk);

        // Exit
        builder.setCurrentBlock(exitBlk);
        var result = builder.emitLoad(iPtr);
        builder.emitReturn(result);

        builder.endFunction();
        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // Cast
    // -------------------------------------------------------

    @Test @Order(25)
    void castIntToFloat() throws Exception {
        builder.beginFunction("toFloat", IRType.F64, List.of(), false);
        var intVal   = builder.constInt(42);
        var floatVal = builder.emitCast(intVal, IRType.F64);
        builder.emitReturn(floatVal);
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    @Test @Order(26)
    void castFloatToInt() throws Exception {
        builder.beginFunction("toInt", IRType.I32, List.of(), false);
        var floatVal = builder.constFloat(3.7);
        var intVal   = builder.emitCast(floatVal, IRType.I32);
        builder.emitReturn(intVal);
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    @Test @Order(27)
    void castI32ToI64() throws Exception {
        builder.beginFunction("extend", IRType.I64, List.of(), false);
        var small = builder.constInt(42);
        var large = builder.emitCast(small, IRType.I64);
        builder.emitReturn(large);
        builder.endFunction();

        compileDefault(buildAndGet());
    }

    // -------------------------------------------------------
    // Debug-build (met debuginfo)
    // -------------------------------------------------------

    @Test @Order(28)
    void debugBuildCompiles() throws Exception {
        builder.setLocation("test.lang", 5, 1);
        builder.beginFunction("main", IRType.I32, List.of(), false);
        builder.setLocation("test.lang", 6, 5);
        builder.emitReturn(builder.constInt(0));
        builder.endFunction();

        compileDebug(buildAndGet());
    }

    // -------------------------------------------------------
    // Optimalisatieniveaus
    // -------------------------------------------------------

    @Test @Order(29)
    void optimizationO2() throws Exception {
        builder.beginFunction("main", IRType.I32, List.of(), false);
        var ptr = builder.emitAlloca("x", IRType.I32);
        builder.emitStore(ptr, builder.constInt(21));
        var a   = builder.emitLoad(ptr);
        var res = builder.emitBinaryOp(Op.ADD, a, a);
        builder.emitReturn(res);
        builder.endFunction();

        Path out = tempDir.resolve("o2.o");
        new NativeLLVMBackend().compile(buildAndGet(),
                CompileOptions.release(), out);
        assertTrue(out.toFile().exists());
    }

    // -------------------------------------------------------
    // .ll bestand wordt aangemaakt
    // -------------------------------------------------------

    @Test @Order(30)
    void llFileIsGenerated() throws Exception {
        builder.beginFunction("main", IRType.I32, List.of(), false);
        builder.emitReturn(builder.constInt(0));
        builder.endFunction();

        Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compile(buildAndGet(),
                CompileOptions.defaults(), out);

        Path ll = tempDir.resolve("out.ll");
        assertTrue(ll.toFile().exists(), ".ll bestand niet aangemaakt");
        assertTrue(ll.toFile().length() > 0, ".ll bestand is leeg");
    }
}
