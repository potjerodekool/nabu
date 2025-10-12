package io.github.potjerodekool.nabu.compiler.backend.graph;

import io.github.potjerodekool.nabu.compiler.backend.ir.Frame;
import io.github.potjerodekool.nabu.compiler.backend.ir.ProcFrag;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.IrCleaner;
import io.github.potjerodekool.nabu.tree.Tag;
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
                new TempExpr(0, IPrimitiveType.INT),
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
                        new TempExpr(Frame.V0)
                )
        ));

        final var body = IrCleaner.cleanUp(new ProcFrag(statements))
                .getBody();

        final var flowGraph = IRFlowGraphBuilder.build(
                body
        );

        final var newStatements = new ArrayList<IStatement>();

        for (final IStatement statement : body) {
            newStatements.add(statement);

            if (statement instanceof ILabelStatement) {
                final var node = flowGraph.getRevMap().get(statement);
                if (node.succ().isEmpty()) {
                    final var move = new Move(
                            new TempExpr(-1, null),
                            new TempExpr(Frame.V0)
                    );

                    newStatements.add(move);
                    newStatements.add(new ILabelStatement());
                }
            }
        }

        final var analiser = new LivenessAnaliser(flowGraph);
        analiser.getFlowGraph();
    }

    @Test
    void fix() {
        final var statements = new ArrayList<IStatement>();
        statements.add(new ILabelStatement());

        final var flowGraph = IRFlowGraphBuilder.build(
                statements
        );
/*
        final var lastNode = flowGraph.getLastNode();
        final var lastStatement = flowGraph.getMap().get(lastNode);

        final var addReturn = !(lastStatement instanceof Move move
                && move.getDst() instanceof TempExpr dest
                && dest.getTemp().getIndex() == Frame.RV);

        if (addReturn) {
            final var lastStatementIndex = statements.indexOf(lastStatement);

            statements.add(lastStatementIndex + 1, new Move(
                    new TempExpr(-1, null),
                    new TempExpr(Frame.V0)
            ));
        }
*/

        System.out.println(statements);
    }

    @Test
    void test() {
        final var frame = new Frame();
        frame.allocateLocal("this", IReferenceType.createClassType(null, "MyClass", List.of()), false);

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
        statements.add(new Move(new Const(0), new TempExpr(2, IPrimitiveType.INT)));
        statements.add(new ILabelStatement());
        statements.add(new Move(new Const(0), new TempExpr(3, IPrimitiveType.INT)));
        statements.add(new ILabelStatement(checkLabel));

        statements.add(new CJump(
                Tag.LT,
                new BinOp(
                        new TempExpr(3, IPrimitiveType.INT),
                        Tag.LT,
                        new TempExpr(1, IPrimitiveType.INT)
                ),
                new Const(1),
                trueLabel,
                falseLabel
        ));
        statements.add(new ILabelStatement(falseLabel));
        statements.add(new ILabelStatement());
        statements.add(new IExpressionStatement(
                new BinOp(
                        new TempExpr(2, IPrimitiveType.INT),
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
                                new TempExpr(3, IPrimitiveType.INT)
                        )
                )
        ));
        statements.add(new Jump(checkLabel));
        statements.add(new ILabelStatement(trueLabel));
        statements.add(new ILabelStatement());
        statements.add(new Move(
                new TempExpr(2, IPrimitiveType.INT),
                new TempExpr(Frame.V0)
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
            System.out.println(statement + " ::: " + i + " TO " + success);
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

