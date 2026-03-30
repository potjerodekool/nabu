package io.github.potjerodekool.nabu.compiler.backend;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.llvm.LLVM.*;

import java.util.function.Supplier;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Bouwt loop-constructies als LLVM basisblokken.
 *
 * Alle drie loop-typen worden omgezet naar hetzelfde basisblok-patroon:
 *
 * While:
 *   → cond-block → body-block → cond-block (lus)
 *                → exit-block
 *
 * For (= while met init + update):
 *   → init (in huidig blok) → cond-block → body-block → update-block → cond-block
 *                                        → exit-block
 *
 * Do-while:
 *   → body-block → cond-block → body-block (lus)
 *                             → exit-block
 *
 * Gebruik:
 *   LoopBuilder lb = new LoopBuilder(builder, ctx, llvmFn);
 *
 *   // while (i < 10) { ... }
 *   lb.buildWhile(
 *       () -> /* emit conditie, return LLVMValueRef i1 *\/,
 *       () -> /* emit body *\/
 *   );
 */
public class LoopBuilder {

    private final LLVMBuilderRef builder;
    private final LLVMContextRef ctx;
    private final LLVMValueRef   fn;

    private int loopCounter = 0;

    public LoopBuilder(LLVMBuilderRef builder,
                       LLVMContextRef ctx,
                       LLVMValueRef fn) {
        this.builder = builder;
        this.ctx     = ctx;
        this.fn      = fn;
    }

    // -------------------------------------------------------
    // While-loop
    //
    //   while (cond) { body }
    //
    //   condBlock:
    //     %c = <condEmitter>
    //     br i1 %c, bodyBlock, exitBlock
    //   bodyBlock:
    //     <bodyEmitter>
    //     br condBlock
    //   exitBlock:
    // -------------------------------------------------------

    public LLVMBasicBlockRef buildWhile(
            Supplier<LLVMValueRef> condEmitter,
            Runnable bodyEmitter) {

        int id = loopCounter++;
        LLVMBasicBlockRef condBlock = appendBlock("while.cond." + id);
        LLVMBasicBlockRef bodyBlock = appendBlock("while.body." + id);
        LLVMBasicBlockRef exitBlock = appendBlock("while.exit." + id);

        // Sprong naar condBlock vanuit huidig blok
        LLVMBuildBr(builder, condBlock);

        // Conditieblok
        LLVMPositionBuilderAtEnd(builder, condBlock);
        LLVMValueRef cond = condEmitter.get();
        LLVMBuildCondBr(builder, cond, bodyBlock, exitBlock);

        // Body
        LLVMPositionBuilderAtEnd(builder, bodyBlock);
        bodyEmitter.run();
        if (!isTerminated(bodyBlock))
            LLVMBuildBr(builder, condBlock);

        // Verder na de loop
        LLVMPositionBuilderAtEnd(builder, exitBlock);
        return exitBlock;
    }

    // -------------------------------------------------------
    // For-loop
    //
    //   for (init; cond; update) { body }
    //
    //   <initEmitter>  (in huidig blok)
    //   condBlock:
    //     %c = <condEmitter>
    //     br i1 %c, bodyBlock, exitBlock
    //   bodyBlock:
    //     <bodyEmitter>
    //     br updateBlock
    //   updateBlock:
    //     <updateEmitter>
    //     br condBlock
    //   exitBlock:
    // -------------------------------------------------------

    public LLVMBasicBlockRef buildFor(
            Runnable initEmitter,
            Supplier<LLVMValueRef> condEmitter,
            Runnable updateEmitter,
            Runnable bodyEmitter) {

        int id = loopCounter++;
        LLVMBasicBlockRef condBlock   = appendBlock("for.cond."   + id);
        LLVMBasicBlockRef bodyBlock   = appendBlock("for.body."   + id);
        LLVMBasicBlockRef updateBlock = appendBlock("for.update." + id);
        LLVMBasicBlockRef exitBlock   = appendBlock("for.exit."   + id);

        // Init in huidig blok, dan naar condBlock
        initEmitter.run();
        LLVMBuildBr(builder, condBlock);

        // Conditieblok
        LLVMPositionBuilderAtEnd(builder, condBlock);
        LLVMValueRef cond = condEmitter.get();
        LLVMBuildCondBr(builder, cond, bodyBlock, exitBlock);

        // Body
        LLVMPositionBuilderAtEnd(builder, bodyBlock);
        bodyEmitter.run();
        if (!isTerminated(bodyBlock))
            LLVMBuildBr(builder, updateBlock);

        // Update
        LLVMPositionBuilderAtEnd(builder, updateBlock);
        updateEmitter.run();
        LLVMBuildBr(builder, condBlock);

        // Verder na de loop
        LLVMPositionBuilderAtEnd(builder, exitBlock);
        return exitBlock;
    }

    // -------------------------------------------------------
    // Do-while-loop
    //
    //   do { body } while (cond);
    //
    //   bodyBlock:
    //     <bodyEmitter>
    //     br condBlock
    //   condBlock:
    //     %c = <condEmitter>
    //     br i1 %c, bodyBlock, exitBlock
    //   exitBlock:
    // -------------------------------------------------------

    public LLVMBasicBlockRef buildDoWhile(
            Runnable bodyEmitter,
            Supplier<LLVMValueRef> condEmitter) {

        int id = loopCounter++;
        LLVMBasicBlockRef bodyBlock = appendBlock("dowhile.body." + id);
        LLVMBasicBlockRef condBlock = appendBlock("dowhile.cond." + id);
        LLVMBasicBlockRef exitBlock = appendBlock("dowhile.exit." + id);

        // Sprong naar body vanuit huidig blok
        LLVMBuildBr(builder, bodyBlock);

        // Body
        LLVMPositionBuilderAtEnd(builder, bodyBlock);
        bodyEmitter.run();
        if (!isTerminated(bodyBlock))
            LLVMBuildBr(builder, condBlock);

        // Conditieblok
        LLVMPositionBuilderAtEnd(builder, condBlock);
        LLVMValueRef cond = condEmitter.get();
        LLVMBuildCondBr(builder, cond, bodyBlock, exitBlock);

        // Verder na de loop
        LLVMPositionBuilderAtEnd(builder, exitBlock);
        return exitBlock;
    }

    // -------------------------------------------------------
    // Hulp
    // -------------------------------------------------------

    private LLVMBasicBlockRef appendBlock(String label) {
        return LLVMAppendBasicBlockInContext(ctx, fn, new BytePointer(label));
    }

    /**
     * Controleert of een basisblok al een terminator heeft.
     * Voorkomt dubbele branch-instructies na een return in de body.
     */
    private boolean isTerminated(LLVMBasicBlockRef block) {
        LLVMValueRef term = LLVMGetBasicBlockTerminator(block);
        return term != null && !term.isNull();
    }
}
