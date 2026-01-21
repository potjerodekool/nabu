package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.Frame;
import io.github.potjerodekool.nabu.compiler.backend.ir.INode;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.BinOp;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.Call;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.Const;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.tools.TodoException;

class StringConcatCodeVisitor implements CodeVisitor<Frame> {

    private final CodeVisitor<Frame> visitor;

    StringConcatCodeVisitor(final CodeVisitor<Frame> visitor) {
        this.visitor = visitor;
    }

    @Override
    public Temp visitUnknown(final INode node, final Frame frame) {
        throw new TodoException(node.getClass().getName());
    }

    @Override
    public Temp visitBinop(final BinOp binOp, final Frame frame) {
        binOp.getLeft().accept(this, frame);
        binOp.getRight().accept(this, frame);
        return null;
    }

    @Override
    public Temp visitConst(final Const cnst, final Frame frame) {
        if (!AsmMethodByteCodeGenerator.STRING_TYPE.equals(cnst.getType())) {
            return cnst.accept(visitor, frame);
        }
        return null;
    }

    @Override
    public Temp visitCall(final Call call, final Frame frame) {
        return call.accept(visitor, frame);
    }

}