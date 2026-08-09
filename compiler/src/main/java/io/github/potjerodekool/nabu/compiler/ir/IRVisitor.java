package io.github.potjerodekool.nabu.compiler.ir;

import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;

public interface IRVisitor<T> {
    T visitBinaryOp(IRInstruction.BinaryOp op);
    T visitConditionalJump(IRInstruction.CondBranch jump);
    T visitUnconditionalJump(IRInstruction.Branch jump);
    //T visitLabel(Label label);
    T visitFunctionCall(IRInstruction.Call call);
    T visitReturn(IRInstruction.Return ret);
    T visitPhi(IRInstruction.Phi phi);
}
