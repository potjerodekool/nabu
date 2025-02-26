package io.github.potjerodekool.nabu.compiler.backend.graph;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.Frame;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.ITypeKind;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.ExpCall;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.MoveCall;
import io.github.potjerodekool.nabu.compiler.tree.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

class LivenessAnaliserTest {

    @Test
    void getFlowGraph() {
        final var statements = new ArrayList<IStatement>();
        final var frame = new Frame();
        frame.allocateLocal("i", IPrimitiveType.INT, true);

        final var binOp = new BinOp(
                new TempExpr(0, frame, IPrimitiveType.INT),
                Tag.EQ,
                new Const(1)
        );

        final var ifExp = new IfThenElseExp(
                new Ex(binOp),
                new Ex(new TempExpr()),
                new Ex(new TempExpr())
        );

        statements.add(ifExp.unNx());
        statements.add(IStatement.seq(
                new ILabelStatement(),
                new Move(
                        new TempExpr(),
                        new TempExpr(Frame.RV, frame, null)
                )
        ));

        final var flowGraph = IRFlowGraphBuilder.build(
                statements,
                new Frame()
        );

        final var analiser = new LivenessAnaliser(flowGraph);
        analiser.getFlowGraph();
    }

    @Test
    void test() {
        final var frame = new Frame();
        frame.allocateLocal("this", IReferenceType.create(ITypeKind.CLASS, "MyClass", List.of()), false);

        frame.allocateLocal(
                "times",
                IPrimitiveType.INT,
                true
        );

        frame.allocateLocal(
                "result",
                IPrimitiveType.INT,
                false
        );

        frame.allocateLocal(
                "i",
                IPrimitiveType.INT,
                false
        );

        final var trueLabel = new ILabel();
        final var falseLabel = new ILabel();
        final var checkLabel = new ILabel();

        final var statements = new ArrayList<IStatement>();
        statements.add(new ILabelStatement());
        statements.add(new Move(new Const(0), new TempExpr(2, frame, IPrimitiveType.INT)));
        statements.add(new ILabelStatement());
        statements.add(new Move(new Const(0), new TempExpr(3, frame, IPrimitiveType.INT)));
        statements.add(new ILabelStatement(checkLabel));

        statements.add(new CJump(
                Tag.LT,
                new BinOp(
                        new TempExpr(3, frame, IPrimitiveType.INT),
                        Tag.LT,
                        new TempExpr(1, frame, IPrimitiveType.INT)
                ),
                new Const(1),
                trueLabel,
                falseLabel
        ));
        statements.add(new ILabelStatement(falseLabel));
        statements.add(new ILabelStatement());
        statements.add(new IExpressionStatement(
                new BinOp(
                        new TempExpr(2, frame, IPrimitiveType.INT),
                        Tag.ADD_ASSIGN,
                        new Const(2)
                )
        ));
        statements.add(new ILabelStatement());
        statements.add(new IExpressionStatement(
                new Eseq(
                        new ILabelStatement(),
                        new Unop(
                                Tag.POST_INC,
                                new TempExpr(3, frame, IPrimitiveType.INT)
                        )
                )
        ));
        statements.add(new Jump(checkLabel));
        statements.add(new ILabelStatement(trueLabel));
        statements.add(new ILabelStatement());
        statements.add(new Move(
                new TempExpr(2, frame, IPrimitiveType.INT),
                new TempExpr(Frame.RV, frame, null)
        ));
        statements.add(new ILabelStatement());

        final var flowGraph = IRFlowGraphBuilder2.build(
                statements
        );

        for (var i = 0; i < statements.size(); i++) {
            final var statement = statements.get(i);

            final var node = flowGraph.revMap.get(statement);
            final var success = node.succ().stream()
                    .map(suc -> {
                        final var sucStatement = flowGraph.map.get(suc);
                        return statements.indexOf(sucStatement);
                    }).map(Object::toString)
                    .collect(Collectors.joining(","));
            System.out.println(statement + " ::: " + Integer.toString(i) + " TO " + success);
            System.out.println("*******");
        }

        final var analiser = new LivenessAnaliser(flowGraph);
        final var livenessGraph = new LivenessGraph(analiser);

        analiser.getFlowGraph();

        /*
        final var labeler = new LocalLabeler(flowGraph);

        statements.forEach(statement -> statement.accept(labeler, null));

        */
    }
}

