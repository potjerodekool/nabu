package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.ExpCall;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.MoveCall;

public interface CodeVisitor<P> {
    Temp visitConst(Const aConst, P param);

    void visitCJump(CJump cJump, P param);

    void visitJump(Jump jump, P param);

    Temp visitName(Name name, P param);

    void visitExpressionStatement(IExpressionStatement expressionStatement, P param);

    void visitMove(Move move, P param);

    Temp visitMem(Mem mem, P param);

    Temp visitTemp(TempExpr tempExpr, P param);

    Temp visitCall(Call call, P param);

    Temp visitEseq(Eseq eseq, P param);

    void visitSeq(Seq seq, P param);

    Temp visitExpList(ExpList expList, P param);

    Temp visitBinop(BinOp binOp, P param);

    void visitLabelStatement(ILabelStatement labelStatement, P param);

    void visitMoveCall(MoveCall moveCall, P param);

    void visitExpCall(ExpCall expCall, P param);

    void visitThrowStatement(IThrowStatement throwStatement, P param);

    Temp visitUnop(Unop unop, P param);

    Temp visitFieldAccess(IFieldAccess fieldAccess, P param);

    void visitBlockStatement(IBlockStatement blockStatement, P param);

    void visitVariableDeclaratorStatement(IVariableDeclaratorStatement variableDeclaratorStatement, P param);

    Temp visitCastExpression(CastExpression castExpression, P param);
}
