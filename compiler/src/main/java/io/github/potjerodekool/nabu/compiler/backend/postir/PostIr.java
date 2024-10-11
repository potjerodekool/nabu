package io.github.potjerodekool.nabu.compiler.backend.postir;

import io.github.potjerodekool.nabu.compiler.backend.graph.IRFlowGraphBuilder;
import io.github.potjerodekool.nabu.compiler.backend.graph.LivenessAnaliser;
import io.github.potjerodekool.nabu.compiler.backend.graph.LivenessGraph;
import io.github.potjerodekool.nabu.compiler.backend.ir.ProcFrag;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.BasicBlocks;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.Canon;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.TraceSchedule;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.CFunction;

import java.util.Objects;

public class PostIr extends AbstractTreeVisitor<Object, Object> {

    @Override
    public Object visitFunction(final CFunction function, final Object param) {
        var procFrag = function.methodSymbol.getFrag();
        procFrag = canonizeProcFrag(procFrag);
        procFrag = basicBlocks(procFrag);

        final var flowGraph = IRFlowGraphBuilder.build(
                procFrag.getBody(),
                procFrag.getFrame()
        );

        final var livenessGraph = new LivenessGraph(new LivenessAnaliser(flowGraph));

        return null;
    }

    private ProcFrag canonizeProcFrag(final ProcFrag procFrag) {
        if (procFrag.getBody().isEmpty()) {
            return procFrag;
        } else {
            final var newBody = Canon.linearize(procFrag.getBody().getFirst());

            newBody.forEach(Objects::requireNonNull);

            return new ProcFrag(
                    procFrag.getFlags(),
                    procFrag.getName(),
                    procFrag.getParams(),
                    procFrag.getReturnType(),
                    procFrag.getFrame(),
                    newBody
            );
        }
    }

    private ProcFrag basicBlocks(final ProcFrag procFrag) {
        if (procFrag.getBody().isEmpty()) {
            return procFrag;
        } else {
            final var basicBlocks = new BasicBlocks(procFrag.getBody());
            final var traceSchedule = new TraceSchedule(basicBlocks);

            return new ProcFrag(
                    procFrag.getFlags(),
                    procFrag.getName(),
                    procFrag.getParams(),
                    procFrag.getReturnType(),
                    procFrag.getFrame(),
                    traceSchedule.getProgram()
            );
        }
    }
}
