package io.github.potjerodekool.nabu.compiler.backend.postir.canon;

import io.github.potjerodekool.nabu.compiler.backend.ir.Frame;
import io.github.potjerodekool.nabu.compiler.backend.ir.ProcFrag;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.Const;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.TempExpr;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.tree.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

class IrCleanerTest {

    @Test
    void cleanUp() {
        final var body = new ArrayList<IStatement>();

        final var trueLabel = new ILabel();
        final var falseLabel = new ILabel();

        body.add(
        new Seq(
                new Seq(
                        new ILabelStatement(),
                        new Seq(
                                new Seq(
                                        new CJump(
                                                Tag.EQ,
                                                new TempExpr(1, IPrimitiveType.INT),
                                                new Const(1),
                                                trueLabel,
                                                falseLabel
                                        ),
                                        new Seq(
                                                new Seq(
                                                        new ILabelStatement(trueLabel),
                                                        new Seq(
                                                                new ILabelStatement(),
                                                                new Move(
                                                                        new Const(true),
                                                                        new TempExpr(Frame.V0)
                                                                )
                                                        )
                                                ),
                                                new ILabelStatement()
                                        )
                                ),
                                new ILabelStatement()
                        )
                ),
                new ILabelStatement(falseLabel)
        ));

        final var frag = new ProcFrag(
                body
        );
        IrCleaner.cleanUp(frag);

    }

    @Test
    void doCleanUp() {
        final var body = new ArrayList<IStatement>();

        final var trueLabel = new ILabel();
        final var falseLabel = new ILabel();

        final var endLabel = new ILabel();

        body.add(new ILabelStatement());
        body.add(new CJump(
                Tag.EQ,
                new TempExpr(1, IPrimitiveType.INT),
                new Const(1),
                trueLabel,
                falseLabel
        ));
        body.add(new ILabelStatement(trueLabel));
        body.add(new Move(
                new Const(true),
                new TempExpr(Frame.V0)
        ));
        body.add(new Jump(endLabel));
        body.add(new ILabelStatement(falseLabel));
        body.add(new Move(
                new Const(false),
                new TempExpr(Frame.V0)
        ));
        body.add(new ILabelStatement());
        body.add(new Jump(endLabel));
        body.add(new ILabelStatement(endLabel));
        body.add(new ILabelStatement());

        var frag = new ProcFrag(
                body
        );

        frag = IrCleaner.basicBlocks(frag);
        IrCleaner.doCleanUp(frag);
        System.out.println(frag);
    }

    @Test
    void testCleanUp() {
        final var body = new ArrayList<IStatement>();

        body.add(new Move(
                new Const(10),
                new TempExpr(1, IPrimitiveType.INT)
        ));
        body.add(new Move(
                new TempExpr(),
                new TempExpr(Frame.V0)
        ));

        var frag = new ProcFrag(
                body
        );

        frag = IrCleaner.basicBlocks(frag);
        IrCleaner.doCleanUp(frag);
    }
}