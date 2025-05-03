package io.github.potjerodekool.nabu.compiler.backend.graph;

import io.github.potjerodekool.nabu.compiler.backend.ir.InvocationType;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.Call;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.Const;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.Name;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.BasicBlocks;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.Canon;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IRFlowGraphBuilderTest {

    @Test
    void build() {
        final var statements = new ArrayList<IStatement>();
        statements.add(new ILabelStatement());

        final var callPrintln = new IExpressionStatement(
                new Call(
                        InvocationType.VIRTUAL,
                        IReferenceType.createClassType(
                                null,
                                "java.io.PrintStream",
                                List.of()
                        ),
                        new Name("println"),
                        IPrimitiveType.VOID,
                        List.of(
                            IReferenceType.createClassType(
                                    null,
                                    "java.lang.String",
                                    List.of()
                            )),
                        List.of(
                                new Const("Hello world!")
                        )
                )
        );


        statements.add(callPrintln);
        statements.add(new ILabelStatement());

        final var newBody = Canon.linearize(Seq.seq(statements));

        final var basicBlock = new BasicBlocks(statements);
        final var blocks = basicBlock.getBlocks();
        final var endLabel = (ILabelStatement) blocks.getLast().getLast();

        for (int blockIndex = 0, lastIndex = blocks.size() - 1; blockIndex < lastIndex; blockIndex++) {
            final var blockStatements = blocks.get(blockIndex);
            final var lastStatementInBlock = blockStatements.getLast();
            if (!lastStatementInBlock.isJump()) {
                blockStatements.add(new Jump(endLabel.getLabel()));
            }
        }

        final var flowGraph = IRFlowGraphBuilder.build(statements);
        System.out.println(flowGraph);

    }
}
