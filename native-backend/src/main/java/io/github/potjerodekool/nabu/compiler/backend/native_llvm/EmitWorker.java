package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.PhiElimination;

/**
 * What emits function bodies: an {@link InstructionEmitter} and a {@link FunctionEmitter} that work in one LLVM context
 * with one builder, on the module state that they share ({@link ModuleEmitState}). There is one worker today, used by
 * the thread that emits the module; a second thread would need a worker with a context of its own (see
 * docs/parallel-emission-design.md).
 */
final class EmitWorker {

    private final FunctionEmitter functions;

    EmitWorker(final FunctionEmitter functions) {
        this.functions = functions;
    }

    /**
     * Emits the body of one function.
     *
     * @return the number of instructions of the body, 0 for a function without one
     */
    long emitBody(final IRFunction fn) {
        long instructionCount = 0;
        // The same preparation as the ASM backend: phis are SSA artefacts, replaced by stores per predecessor before
        // the emission (otherwise a phi incoming refers to a temp that is defined in another block).
        if (!fn.isExternal()) {
            PhiElimination.run(fn);
            for (final var block : fn.blocks()) {
                instructionCount += block.instructions().size();
            }
        }
        functions.emitBody(fn);
        return instructionCount;
    }

    /** The debug info of the bodies so far is finished (it is per module). */
    void finishDebugInfo() {
        functions.finishDebugInfo();
    }
}
