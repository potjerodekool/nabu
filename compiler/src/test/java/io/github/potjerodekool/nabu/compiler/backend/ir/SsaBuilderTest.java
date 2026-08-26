package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.backend.CompileException;
import io.github.potjerodekool.nabu.compiler.backend.CompileOptions;
import io.github.potjerodekool.nabu.compiler.backend.asm.AsmBackend;
import io.github.potjerodekool.nabu.compiler.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.compiler.ir.IRBuilder;
import io.github.potjerodekool.nabu.compiler.ir.IRFunction;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SsaBuilderTest {

    // -------------------------------------------------------
    // Helper: telt het aantal instructies van een bepaald type
    // -------------------------------------------------------

    private long countInstructions(IRFunction function, Class<? extends IRInstruction> type) {
        return function.blocks().stream()
                .flatMap(b -> b.instructions().stream())
                .filter(type::isInstance)
                .count();
    }

    private long countPhis(IRFunction function) {
        return countInstructions(function, IRInstruction.Phi.class);
    }

    private long countMoves(IRFunction function) {
        return countInstructions(function, IRInstruction.Move.class);
    }

    private long countStores(IRFunction function) {
        return countInstructions(function, IRInstruction.Store.class);
    }

    private long countAllocas(IRFunction function) {
        return countInstructions(function, IRInstruction.Alloca.class);
    }

    private long countLoads(IRFunction function) {
        return countInstructions(function, IRInstruction.Load.class);
    }

    // -------------------------------------------------------
    // Diamond-CFG: if-else met één variabele
    //
    //   entry:
    //     %x.ptr = alloca i32
    //     store %x.ptr, 10
    //     %cond = gt %x.ptr, 0
    //     br %cond, then, else
    //
    //   then:
    //     store %x.ptr, 42
    //     br merge
    //
    //   else:
    //     store %x.ptr, 99
    //     br merge
    //
    //   merge:
    //     %v = load %x.ptr
    //     ret %v
    //
    // Na SSA:
    //   entry:   %cond = gt 10, 0; br then, else  (geen alloca/store/load meer)
    //   then:    br merge
    //   else:    br merge
    //   merge:   %x.1 = phi(10 from entry, 42 from then, 99 from else)
    //            %v = move %x.1
    //            ret %v
    // -------------------------------------------------------

    @Test
    void testDiamondOneVariable() {
        final var builder = new IRBuilder("test");
        builder.setLocation("test.lang", 1, 1);

        final var param = new IRValue.Temp("%x", IRType.I32);
        builder.beginFunction("foo", IRType.I32, List.of(param), 0);

        // entry block
        final var entry = builder.currentBlock();
        final var xPtr = builder.emitAlloca("x", IRType.I32);
        builder.emitStore(xPtr, builder.constInt(10));

        final var cond = builder.emitBinaryOp(
                IRInstruction.BinaryOp.Op.GT,
                builder.emitLoad(xPtr),
                builder.constInt(0));

        final var thenBlk = builder.beginBlock("then");
        final var elseBlk = builder.beginBlock("else");

        builder.setCurrentBlock(entry);
        builder.emitCondBranch(cond, thenBlk, elseBlk);

        // then
        builder.setCurrentBlock(thenBlk);
        builder.emitStore(xPtr, builder.constInt(42));

        // else
        builder.setCurrentBlock(elseBlk);
        builder.emitStore(xPtr, builder.constInt(99));

        // merge
        final var mergeBlk = builder.beginBlock("merge");
        builder.setCurrentBlock(thenBlk);
        builder.emitBranch(mergeBlk);
        builder.setCurrentBlock(elseBlk);
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(mergeBlk);
        final var loaded = builder.emitLoad(xPtr);
        builder.emitReturn(loaded);

        builder.endFunction();

        final var module = builder.build();
        final var function = module.functions().getFirst();

        // Vóór SSA: alloca, 3 stores, 2 loads
        assertTrue(countAllocas(function) >= 1, "Minstens 1 alloca vóór SSA");
        assertTrue(countStores(function) >= 3, "Minstens 3 stores vóór SSA");

        // Voer SSA uit
        SsaBuilder.run(module);

        // Na SSA:
        // - Geen phi's verwacht: de entry store bereikt merge niet direct
        //   (entry → then/else → merge, de phi komt van then en else)
        // - Alles minstens 1 phi (van then en else)
        assertTrue(countPhis(function) >= 1, "Minstens 1 phi na SSA");
    }

    // -------------------------------------------------------
    // Variabele alleen in één tak — geen phi nodig
    //
    //   entry:
    //     %x.ptr = alloca i32
    //     store %x.ptr, 10
    //     br then, merge
    //
    //   then:
    //     store %x.ptr, 42
    //     br merge
    //
    //   merge:
    //     %v = load %x.ptr
    //     ret %v
    //
    // Alleen entry en then schrijven naar x.
    // Maar both bereiken merge → phi nodig.
    // -------------------------------------------------------

    @Test
    void testVariableOnlyInOneBranch() {
        final var builder = new IRBuilder("test");
        builder.setLocation("test.lang", 1, 1);

        builder.beginFunction("oneBranch", IRType.I32, List.of(), 0);

        final var entry = builder.currentBlock();
        final var xPtr = builder.emitAlloca("x", IRType.I32);
        builder.emitStore(xPtr, builder.constInt(10));

        final var thenBlk = builder.beginBlock("then");
        final var mergeBlk = builder.beginBlock("merge");

        builder.setCurrentBlock(entry);
        builder.emitCondBranch(builder.constBool(true), thenBlk, mergeBlk);

        builder.setCurrentBlock(thenBlk);
        builder.emitStore(xPtr, builder.constInt(42));
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(mergeBlk);
        final var loaded = builder.emitLoad(xPtr);
        builder.emitReturn(loaded);

        builder.endFunction();

        final var module = builder.build();
        SsaBuilder.run(module);

        final var function = module.functions().getFirst();

        // Phi verwacht: entry(10) and then(42) komen samen in merge
        assertTrue(countPhis(function) >= 1, "Phi verwacht op merge-punt");
    }

    // -------------------------------------------------------
    // Geen variabele — geen phi nodig
    // -------------------------------------------------------

    @Test
    void testNoVariablesNoPhis() {
        final var builder = new IRBuilder("test");
        builder.beginFunction("simple", IRType.I32, List.of(), 0);

        builder.emitReturn(builder.constInt(42));
        builder.endFunction();

        final var module = builder.build();
        final var function = module.functions().getFirst();

        final var phisBefore = countPhis(function);
        SsaBuilder.run(module);

        // Geen variabelen → geen phis
        assertEquals(phisBefore, countPhis(function), "Geen phis verwacht");
    }

    // -------------------------------------------------------
    // Twee variabelen in diamond — twee phis
    // -------------------------------------------------------

    @Test
    void testTwoVariablesInDiamond() {
        final var builder = new IRBuilder("test");
        builder.setLocation("test.lang", 1, 1);

        builder.beginFunction("twoVars", IRType.I32, List.of(), 0);

        final var entry = builder.currentBlock();
        final var aPtr = builder.emitAlloca("a", IRType.I32);
        final var bPtr = builder.emitAlloca("b", IRType.I32);
        builder.emitStore(aPtr, builder.constInt(1));
        builder.emitStore(bPtr, builder.constInt(10));

        final var cond = builder.constBool(true);
        final var thenBlk = builder.beginBlock("then");
        final var elseBlk = builder.beginBlock("else");

        builder.setCurrentBlock(entry);
        builder.emitCondBranch(cond, thenBlk, elseBlk);

        builder.setCurrentBlock(thenBlk);
        builder.emitStore(aPtr, builder.constInt(2));
        builder.emitStore(bPtr, builder.constInt(20));

        builder.setCurrentBlock(elseBlk);
        builder.emitStore(aPtr, builder.constInt(3));
        builder.emitStore(bPtr, builder.constInt(30));

        final var mergeBlk = builder.beginBlock("merge");
        builder.setCurrentBlock(thenBlk);
        builder.emitBranch(mergeBlk);
        builder.setCurrentBlock(elseBlk);
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(mergeBlk);
        final var aVal = builder.emitLoad(aPtr);
        final var bVal = builder.emitLoad(bPtr);
        final var sum = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, aVal, bVal);
        builder.emitReturn(sum);

        builder.endFunction();

        final var module = builder.build();
        SsaBuilder.run(module);

        final var function = module.functions().getFirst();

        // Twee variabelen → minstens 2 phis
        assertTrue(countPhis(function) >= 2, "Minstens 2 phis voor twee variabelen");
    }

    // -------------------------------------------------------
    // While-lus — phi op loop-header
    // -------------------------------------------------------

    @Test
    void testWhileLoop() throws IOException, CompileException {
        final var oldOut = System.out;

        //System.setOut(new DelegatePrintStream(oldOut));

        final var builder = new IRBuilder("test");
        builder.setLocation("test.lang", 1, 1);

        builder.beginFunction("loop", IRType.VOID, List.of(), Flags.STATIC);

        final var entry = builder.currentBlock();

        final var resultPtr = builder.emitAlloca("result", IRType.I32);
        builder.emitStore(resultPtr, builder.constInt(25));

        final var xPtr = builder.emitAlloca("x", IRType.I32);
        builder.emitStore(xPtr, builder.constInt(12321321));

        final var condBlk = builder.beginBlock("while.cond");
        final var bodyBlk = builder.beginBlock("while.body");
        final var exitBlk = builder.beginBlock("while.exit");

        builder.setCurrentBlock(entry);
        builder.emitBranch(condBlk);

        builder.setCurrentBlock(condBlk);
        final var loaded = builder.emitLoad(xPtr);
        final var cond = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.LT, loaded, builder.constInt(10));
        builder.emitCondBranch(cond, bodyBlk, exitBlk);

        builder.setCurrentBlock(bodyBlk);
        final var bodyLoaded = builder.emitLoad(xPtr);
        final var inc = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, bodyLoaded, builder.constInt(1));
        builder.emitStore(xPtr, inc);

        final var resultLoaded = builder.emitLoad(resultPtr);
        final var incResult = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, resultLoaded, builder.constInt(1));
        builder.emitStore(resultPtr, incResult);

        builder.emitBranch(condBlk);

        builder.setCurrentBlock(exitBlk);
        builder.emitReturn(null);

        builder.endFunction();

        final var module = builder.build();
        SsaBuilder.run(module);
        PhiElimination.run(module);

        final var function = module.functions().getFirst();

        final var code = InstructionPrinter.print(function.blocks());
        System.out.println(code);

        final var backend = new AsmBackend();
        final var output = Paths.get("C:\\projects\\nabu\\compiler\\test-out");

        if (!Files.exists(output)) {
            Files.createDirectories(output);
        }
        backend.compile(module, CompileOptions.defaults(), output);

        // Loop header (while.cond) heeft 2 predecessors: entry en while.body
        // → phi nodig voor x op while.cond
        //assertTrue(countPhis(function) >= 1, "Minstens 1 phi op loop-header");
    }

    // -------------------------------------------------------
    // Geneste if — meerdere merge-punten
    // -------------------------------------------------------

    @Test
    void testNestedIf() {
        final var builder = new IRBuilder("test");
        builder.setLocation("test.lang", 1, 1);

        builder.beginFunction("nested", IRType.I32, List.of(), 0);

        final var entry = builder.currentBlock();
        final var xPtr = builder.emitAlloca("x", IRType.I32);
        builder.emitStore(xPtr, builder.constInt(0));

        // Outer if
        final var outerThen = builder.beginBlock("outer.then");
        final var outerElse = builder.beginBlock("outer.else");
        final var outerMerge = builder.beginBlock("outer.merge");

        builder.setCurrentBlock(entry);
        builder.emitCondBranch(builder.constBool(true), outerThen, outerElse);

        // Outer then: x = 1
        builder.setCurrentBlock(outerThen);
        builder.emitStore(xPtr, builder.constInt(1));

        // Inner if
        final var innerThen = builder.beginBlock("inner.then");
        final var innerElse = builder.beginBlock("inner.else");
        final var innerMerge = builder.beginBlock("inner.merge");

        builder.setCurrentBlock(outerThen);
        builder.emitCondBranch(builder.constBool(true), innerThen, innerElse);

        builder.setCurrentBlock(innerThen);
        builder.emitStore(xPtr, builder.constInt(10));

        builder.setCurrentBlock(innerElse);
        builder.emitStore(xPtr, builder.constInt(20));

        builder.setCurrentBlock(innerMerge);
        builder.emitBranch(outerMerge);

        builder.setCurrentBlock(innerThen);
        builder.emitBranch(innerMerge);

        builder.setCurrentBlock(innerElse);
        builder.emitBranch(innerMerge);

        // Outer else: x = 2
        builder.setCurrentBlock(outerElse);
        builder.emitStore(xPtr, builder.constInt(2));
        builder.emitBranch(outerMerge);

        builder.setCurrentBlock(outerMerge);
        final var loaded = builder.emitLoad(xPtr);
        builder.emitReturn(loaded);

        builder.endFunction();

        final var module = builder.build();
        SsaBuilder.run(module);

        final var function = module.functions().getFirst();

        // Minstens 2 phis: één op innerMerge, één op outerMerge
        assertTrue(countPhis(function) >= 2,
                "Minstens 2 phis bij geneste if (innerMerge + outerMerge)");
    }
}


class InstructionPrinter {

    private final StringBuilder builder = new StringBuilder();

    public static String print(final List<IRBasicBlock> blocks) {
        final var printer = new InstructionPrinter();
        blocks.forEach(printer::printBlock);
        return printer.builder.toString();
    }

    private void printBlock(final IRBasicBlock block) {
        builder.append(block.label()).append(":\n");
        block.instructions().forEach(instruction -> {
            builder.append("    ");
            accept(instruction);
            builder.append('\n');
        });
    }

    void accept(final IRInstruction instruction) {
        switch (instruction) {
            case IRInstruction.Alloca alloca -> acceptAlloca(alloca);
            case IRInstruction.AllocaArray allocaArray -> acceptAllocaArray(allocaArray);
            case IRInstruction.ArrayLength arrayLength -> acceptArrayLength(arrayLength);
            case IRInstruction.ArrayLoad arrayLoad -> acceptArrayLoad(arrayLoad);
            case IRInstruction.ArrayStore arrayStore -> acceptArrayStore(arrayStore);
            case IRInstruction.BinaryOp binaryOp -> acceptBinaryOp(binaryOp);
            case IRInstruction.Branch branch -> acceptBranch(branch);
            case IRInstruction.Call call -> acceptCall(call);
            case IRInstruction.Cast cast -> acceptCast(cast);
            case IRInstruction.CondBranch condBranch -> acceptCondBranch(condBranch);
            case IRInstruction.HeapAlloc heapAlloc -> acceptHeapAlloc(heapAlloc);
            case IRInstruction.IndirectCall indirectCall -> acceptIndirectCall(indirectCall);
            case IRInstruction.InstanceOf instanceOf -> acceptInstanceOf(instanceOf);
            case IRInstruction.Load load -> acceptLoad(load);
            case IRInstruction.MonitorEnter monitorEnter -> acceptMonitorEnter(monitorEnter);
            case IRInstruction.MonitorExit monitorExit -> acceptMonitorExit(monitorExit);
            case IRInstruction.Move move -> acceptMove(move);
            case IRInstruction.Phi phi -> acceptPhi(phi);
            case IRInstruction.Pop pop -> acceptPop(pop);
            case IRInstruction.Return returnInstruction -> acceptReturn(returnInstruction);
            case IRInstruction.Store store -> acceptStore(store);
            case IRInstruction.Throw throwInstruction -> acceptThrow(throwInstruction);
            case IRInstruction.TryCatchRegion tryCatchRegion -> acceptTryCatchRegion(tryCatchRegion);
        }
    }

    // -------------------------------------------------------
    // Allocaties
    // -------------------------------------------------------

    private void acceptAlloca(final IRInstruction.Alloca alloca) {
        builder.append(render(alloca.result()))
                .append(" = alloca ")
                .append(alloca.allocType());
    }

    private void acceptAllocaArray(final IRInstruction.AllocaArray allocaArray) {
        builder.append(render(allocaArray.result()))
                .append(" = alloca ")
                .append(allocaArray.allocType())
                .append(", size ")
                .append(render(allocaArray.size()));
    }

    // -------------------------------------------------------
    // Array-operaties
    // -------------------------------------------------------

    private void acceptArrayLength(final IRInstruction.ArrayLength arrayLength) {
        builder.append(render(arrayLength.result()))
                .append(" = arraylength ")
                .append(render(arrayLength.array()));
    }

    private void acceptArrayLoad(final IRInstruction.ArrayLoad arrayLoad) {
        builder.append(render(arrayLoad.result()))
                .append(" = load ")
                .append(render(arrayLoad.array()))
                .append('[')
                .append(render(arrayLoad.index()))
                .append(']');
    }

    private void acceptArrayStore(final IRInstruction.ArrayStore arrayStore) {
        builder.append(render(arrayStore.array()))
                .append('[')
                .append(render(arrayStore.index()))
                .append(']')
                .append(" = ")
                .append(render(arrayStore.value()));
    }

    // -------------------------------------------------------
    // Rekenkundig
    // -------------------------------------------------------

    private void acceptBinaryOp(final IRInstruction.BinaryOp binaryOp) {
        builder.append(render(binaryOp.result()))
                .append(" = ")
                .append(opName(binaryOp.op()))
                .append('(')
                .append(render(binaryOp.left()))
                .append(", ")
                .append(render(binaryOp.right()))
                .append(')');
    }

    private String opName(final IRInstruction.BinaryOp.Op op) {
        return switch (op) {
            case ADD -> "add";
            case SUB -> "sub";
            case MUL -> "mul";
            case DIV -> "div";
            case MOD -> "mod";
            case AND -> "and";
            case OR -> "or";
            case XOR -> "xor";
            case BITAND -> "bitand";
            case BITOR -> "bitor";
            case BITXOR -> "bitxor";
            case EQ -> "eq";
            case NEQ -> "neq";
            case LT -> "lt";
            case LTE -> "lte";
            case GT -> "gt";
            case GTE -> "gte";
        };
    }

    // -------------------------------------------------------
    // Controle-stroom
    // -------------------------------------------------------

    void acceptBranch(final IRInstruction.Branch instruction) {
        builder.append("br ")
                .append(instruction.targetLabel());
    }

    private void acceptCondBranch(final IRInstruction.CondBranch condBranch) {
        builder.append("br ")
                .append(render(condBranch.condition()))
                .append(", ")
                .append(condBranch.trueLabel())
                .append(", ")
                .append(condBranch.falseLabel());
    }

    private void acceptReturn(final IRInstruction.Return returnInstruction) {
        if (returnInstruction.value() == null) {
            builder.append("ret void");
        } else {
            builder.append("ret ")
                    .append(render(returnInstruction.value()));
        }
    }

    // -------------------------------------------------------
    // Aanroepen
    // -------------------------------------------------------

    private void acceptCall(final IRInstruction.Call call) {
        if (call.result() != null) {
            builder.append(render(call.result())).append(" = ");
        }
        builder.append("call ")
                .append(call.returnType())
                .append(" @")
                .append(call.function())
                .append('(')
                .append(call.args().stream()
                        .map(this::render)
                        .collect(Collectors.joining(", ")))
                .append(')');
    }

    private void acceptIndirectCall(final IRInstruction.IndirectCall indirectCall) {
        if (indirectCall.result() != null) {
            builder.append(render(indirectCall.result())).append(" = ");
        }
        builder.append("call indirect ")
                .append(render(indirectCall.callee()))
                .append('(')
                .append(indirectCall.args().stream()
                        .map(this::render)
                        .collect(Collectors.joining(", ")))
                .append(')');
    }

    // -------------------------------------------------------
    // Geheugen
    // -------------------------------------------------------

    private void acceptLoad(final IRInstruction.Load load) {
        builder.append(render(load.result()))
                .append(" = load ")
                .append(load.type())
                .append(' ')
                .append(render(load.ptr()));
    }

    private void acceptStore(final IRInstruction.Store store) {
        builder.append("store ")
                .append(render(store.ptr()))
                .append(", ")
                .append(render(store.value()));
    }

    private void acceptHeapAlloc(final IRInstruction.HeapAlloc heapAlloc) {
        builder.append(render(heapAlloc.result()))
                .append(" = heapalloc ")
                .append(heapAlloc.allocType());
    }

    // -------------------------------------------------------
    // Type-conversie
    // -------------------------------------------------------

    private void acceptCast(final IRInstruction.Cast cast) {
        builder.append(render(cast.result()))
                .append(" = cast ")
                .append(render(cast.source()))
                .append(" to ")
                .append(cast.targetType());
    }

    private void acceptInstanceOf(final IRInstruction.InstanceOf instanceOf) {
        builder.append(render(instanceOf.result()))
                .append(" = instanceof ")
                .append(render(instanceOf.source()))
                .append(", ")
                .append(instanceOf.type());
    }

    // -------------------------------------------------------
    // Synchronisatie
    // -------------------------------------------------------

    private void acceptMonitorEnter(final IRInstruction.MonitorEnter monitorEnter) {
        builder.append("monitorenter ")
                .append(render(monitorEnter.object()));
    }

    private void acceptMonitorExit(final IRInstruction.MonitorExit monitorExit) {
        builder.append("monitorexit ")
                .append(render(monitorExit.object()));
    }

    // -------------------------------------------------------
    // SSA
    // -------------------------------------------------------

    private void acceptMove(final IRInstruction.Move move) {
        builder.append(render(move.result()))
                .append(" = move ")
                .append(render(move.value()));
    }

    private void acceptPhi(final IRInstruction.Phi phi) {
        builder.append(render(phi.result()))
                .append(" = phi(")
                .append(phi.incomingValues().stream()
                        .map(incoming -> render(incoming.value()) + " from " + incoming.fromBlock().label())
                        .collect(Collectors.joining(", ")))
                .append(')');
    }

    private void acceptPop(final IRInstruction.Pop pop) {
        builder.append("pop ")
                .append(render(pop.result()));
    }

    // -------------------------------------------------------
    // Exceptions
    // -------------------------------------------------------

    private void acceptThrow(final IRInstruction.Throw throwInstruction) {
        builder.append("throw ")
                .append(render(throwInstruction.result()))
                .append(" (")
                .append(throwInstruction.type())
                .append(')');
    }

    private void acceptTryCatchRegion(final IRInstruction.TryCatchRegion tryCatchRegion) {
        builder.append("try-catch [")
                .append(tryCatchRegion.tryStartLabel())
                .append(" .. ")
                .append(tryCatchRegion.tryEndLabel())
                .append("] handler ")
                .append(tryCatchRegion.handlerLabel())
                .append(", exception ")
                .append(tryCatchRegion.exceptionType());
    }

    // -------------------------------------------------------
    // Waarde-rendering
    // -------------------------------------------------------

    private String render(final IRValue value) {
        if (value == null) {
            return "null";
        }

        return switch (value) {
            case IRValue.Temp temp -> temp.name();
            case IRValue.Named named -> named.name();
            case IRValue.ConstInt c -> String.valueOf(c.value());
            case IRValue.ConstFloat f -> renderFloat(f.value());
            case IRValue.ConstBool b -> String.valueOf(b.value());
            case IRValue.ConstString s -> "\"" + s.value().replace("\"", "\\\"") + "\"";
            case IRValue.ConstNull n -> "null";
            case IRValue.ConstUndef u -> "undef";
            case IRValue.ConstClass c -> "class " + c.type();
            case IRValue.Values v -> v.values().stream()
                    .map(this::render)
                    .collect(Collectors.joining(", "));
            case IRValue.FunctionRef f -> "@" + f.name();
        };
    }

    private String renderFloat(final double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return value + ".0";
        }
        return String.valueOf(value);
    }
}

class DelegatePrintStream extends PrintStream {

    public DelegatePrintStream(final OutputStream out) {
        super(out);
    }

    @Override
    public void write(final int b) {
        super.write(b);
    }

    @Override
    public void write(final byte[] buf, final int off, final int len) {
        super.write(buf, off, len);
    }

    @Override
    public void write(final byte[] buf) throws IOException {
        super.write(buf);
    }

    @Override
    public void writeBytes(final byte[] buf) {
        super.writeBytes(buf);
    }

    @Override
    public void print(final boolean b) {
        super.print(b);
    }

    @Override
    public void print(final char c) {
        super.print(c);
    }

    @Override
    public void print(final int i) {
        super.print(i);
    }

    @Override
    public void print(final long l) {
        super.print(l);
    }

    @Override
    public void print(final float f) {
        super.print(f);
    }

    @Override
    public void print(final double d) {
        super.print(d);
    }

    @Override
    public void print(final char[] s) {
        super.print(s);
    }

    @Override
    public void print(final String s) {
        super.print(s);
    }

    @Override
    public void print(final Object obj) {
        super.print(obj);
    }

    @Override
    public void println() {
        super.println();
    }

    @Override
    public void println(final boolean x) {
        super.println(x);
    }

    @Override
    public void println(final char x) {
        super.println(x);
    }

    @Override
    public void println(final int x) {
        super.println(x);
    }

    @Override
    public void println(final long x) {
        super.println(x);
    }

    @Override
    public void println(final float x) {
        super.println(x);
    }

    @Override
    public void println(final double x) {
        super.println(x);
    }

    @Override
    public void println(final char[] x) {
        super.println(x);
    }

    @Override
    public void println(final String x) {
        super.println(x);
    }

    @Override
    public void println(final Object x) {
        super.println(x);
    }

    @Override
    public PrintStream printf(final String format, final Object... args) {
        return super.printf(format, args);
    }

    @Override
    public PrintStream printf(final Locale l, final String format, final Object... args) {
        return super.printf(l, format, args);
    }
}