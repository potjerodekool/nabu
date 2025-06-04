package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.ExpCall;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.MoveCall;

public interface CodeVisitor<P> {

    Temp visitUnknown(INode node, P parm);

    default Temp visitConst(Const cnst, P param) {
        return visitUnknown(cnst, param);
    }

    default void visitCJump(CJump cJump, P param) {
        visitUnknown(cJump, param);
    }

    default void visitJump(Jump jump, P param) {
        visitUnknown(jump, param);
    }

    default Temp visitName(Name name, P param) {
        return visitUnknown(name, param);
    }

    default void visitExpressionStatement(IExpressionStatement expressionStatement, P param) {
        visitUnknown(expressionStatement, param);
    }

    default void visitMove(Move move, P param) {
        visitUnknown(move, param);
    }

    default Temp visitMem(Mem mem, P param) {
        return visitUnknown(mem, param);
    }

    default Temp visitTemp(TempExpr tempExpr, P param) {
        return visitUnknown(tempExpr, param);
    }

    default Temp visitCall(Call call, P param) {
        return visitUnknown(call, param);
    }

    default Temp visitEseq(Eseq eseq, P param) {
        return visitUnknown(eseq, param);
    }

    default void visitSeq(Seq seq, P param) {
        visitUnknown(seq, param);
    }

    default Temp visitExpList(ExpList expList, P param) {
        return visitUnknown(expList, param);
    }

    default Temp visitBinop(BinOp binOp, P param) {
        return visitUnknown(binOp, param);
    }

    default void visitLabelStatement(ILabelStatement labelStatement, P param) {
        visitUnknown(labelStatement, param);
    }

    default void visitMoveCall(MoveCall moveCall, P param) {
        visitUnknown(moveCall, param);
    }

    default void visitExpCall(ExpCall expCall, P param) {
        visitUnknown(expCall, param);
    }

    default void visitThrowStatement(IThrowStatement throwStatement, P param) {
        visitUnknown(throwStatement, param);
    }

    default Temp visitUnop(Unop unop, P param) {
        return visitUnknown(unop, param);
    }

    default Temp visitFieldAccess(IFieldAccess fieldAccess, P param) {
        return visitUnknown(fieldAccess, param);
    }

    default void visitVariableDeclaratorStatement(IVariableDeclaratorStatement variableDeclaratorStatement, P param) {
        visitUnknown(variableDeclaratorStatement, param);
    }

    default Temp visitTypeExpression(ITypeExpression typeExpression, P param) {
        return visitUnknown(typeExpression, param);
    }

    default Temp visitInstExpression(InstExpression instExpression, P param) {
        return visitUnknown(instExpression, param);
    }

    default void visitSwitchStatement(ISwitchStatement switchStatement, P param) {
        visitUnknown(switchStatement, param);
    }

    default Temp visitArrayLoad(ArrayLoad arrayLoad, P param) {
        return visitUnknown(arrayLoad, param);
    }
}
