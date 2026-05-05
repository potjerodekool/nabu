package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.backend.asm.LocalVarManager;
import io.github.potjerodekool.nabu.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.ir.IRFunction;
import io.github.potjerodekool.nabu.ir.IRModule;
import io.github.potjerodekool.nabu.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;

public class IRPrinter {

    private final StringBuilder builder = new StringBuilder();
    private IRInstruction.BinaryOp.Op operator = null;
    private final LocalVarManager localVarManager = new LocalVarManager();

    public static String print(final IRModule module) {
        final var printer = new IRPrinter();
        printer.printModule(module);
        return printer.builder.toString();
    }

    private void printModule(final IRModule module) {
        module.functions().forEach(this::print);
    }

    private void print(final IRFunction function) {
        function.blocks().forEach(this::print);
    }

    private void print(final IRBasicBlock block) {
        var labelName = block.label();
        if (labelName.startsWith("%")) {
            labelName = labelName.substring(1);
        }

        printLn("Label: " + labelName);
        block.instructions().forEach(this::print);
    }

    private void print(final IRInstruction irInstruction) {
        switch (irInstruction) {
            case IRInstruction.Alloca alloca -> printAlloca(alloca);
            case IRInstruction.Store store -> printStore(store);
            case IRInstruction.Branch branch -> printBranch(branch);
            case IRInstruction.CondBranch condBranch -> printCondBranch(condBranch);
            case IRInstruction.Load load -> printLoad(load);
            case IRInstruction.BinaryOp binaryOp -> printBinaryOp(binaryOp);
            case IRInstruction.Return returnInstruction -> printReturn(returnInstruction);

            default -> throw new  IllegalStateException("Unknown IRInstruction " + irInstruction);
        }
    }

    private void printBinaryOp(final IRInstruction.BinaryOp binaryOp) {
        pushValue(binaryOp.left());
        pushValue(binaryOp.right());

        if (binaryOp.op() == IRInstruction.BinaryOp.Op.ADD) {
            printLn(binaryOp.op().toString());
        } else {
            this.operator = binaryOp.op();
        }

        final var result = (IRValue.Temp) binaryOp.result();
        localVarManager.setStackValue(result);
    }

    private void pushValue(final IRValue value) {
        if (value instanceof IRValue.Temp temp) {
            var name = temp.name();

            if (localVarManager.isOnStack(name)) {
                return;
            }

            name = name.substring(1);

            printLn("LOAD " + name);
        } else if (value instanceof IRValue.ConstInt constInt) {
            printLn(Long.toString(constInt.value()));
        }
    }

    private void printBranch(final IRInstruction.Branch branch) {
        printLn("GOTO " + branch.targetLabel());
    }

    private void printCondBranch(final IRInstruction.CondBranch condBranch) {
        printLn(operator.toString() + " GOTO " + condBranch.falseLabel());
        operator = null;
        //stack.pop();
        //stack.pop();
        //stackValues.clear();
    }

    private void printLoad(final IRInstruction.Load load) {
        final var source = resolveName(load.ptr());
        final var index = localVarManager.getSlot(source);

        printLn("LOAD " + index);
        final var result = (IRValue.Temp) load.result();
        localVarManager.setStackValue(result);
    }

    private void printLoad(final int index,
                           final String name) {
        /*
        if (stack.contains(index)) {
            return;
        }
        */

        //push(index);
        printLn("LOAD " + index);
        /*
        if (!stack.isEmpty() && stack.peek() == Integer.valueOf(index)) {
            return;
        }

        if (stackValues.contains(new StackValue(index))) {
            return;
        }

        push(index);
        */
    }

    /*
    private void push(final Object value) {
        this.stack.push(value);
    }
    */

    private void print(final IRValue value) {
        if (value instanceof IRValue.Temp temp) {
            if (localVarManager.isOnStack(temp.name())) {
                return;
            }
        }

        final var str = valueToString(value);
        printLn(str);
    }

    private void printReturn(final IRInstruction.Return returnInstruction) {
        if (returnInstruction.value() != null) {
            print(returnInstruction.value());
        }

        printLn("Return");
    }

    private void printStore(final IRInstruction.Store store) {
        final var target = resolveName(store.ptr());

        if (!(store.value() instanceof IRValue.Temp)) {
            final var value = valueToString(store.value());
            printLn(value);
        }
        final var index = localVarManager.getSlot(target);
        printLn("STORE " + index);
    }

    private String valueToString(final IRValue value) {
        if (value instanceof IRValue.ConstInt(long intValue, IRType type)) {
            if (type == IRType.I32) {
                var i = Long.valueOf(intValue).intValue();
                return Integer.toString(i);
            } else if (type == IRType.I64) {
                return Long.toString(intValue);
            }
        } else if (value instanceof IRValue.Temp(String name, IRType type)) {
            return resolveName(value);
        }

        return value.toString();
    }

    private String resolveName(final IRValue value) {
        if (value instanceof IRValue.Temp(String name, IRType type)) {
            if (name.startsWith("%")) {
                name = name.substring(1);
            }

            if (type instanceof IRType.Ptr) {
                name = name.replace(".ptr", "");
            }

            return name;
        } else {
            throw new IllegalArgumentException("Unknown IRValue " + value);
        }
    }

    private void printAlloca(final IRInstruction.Alloca alloca) {
        final var name =  resolveName(alloca.result());
        //locals.computeIfAbsent(name, s -> new Local(locals.size()));
        localVarManager.allocateSlot(name);
    }

    private void print(final String text) {
        builder.append(text);
    }

    private void printLn(final String text) {
        print(text);
        printLn();
    }

    private void printLn() {
        builder.append("\n");
    }

}

record Local(int index) {

}
