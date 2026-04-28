package io.github.potjerodekool.nabu.ir;

import io.github.potjerodekool.nabu.ir.instructions.IRInstruction;

import java.util.*;

/**
 * JVM Bytecode Code Generator with Fall-Through Optimization.
 *
 * Core optimization:
 * - Linearize basic blocks so that false-branches become fall-throughs
 * - Emit conditional jumps inverted (to the non-fallthrough target)
 * - Eliminate explicit unconditional jumps when possible
 *
 * This matches how javac compiles conditional statements.
 */
public class JVMCodegen implements IRVisitor<Void> {
    private final CFG cfg;
    private final List<String> bytecode;
    private final Map<String, Integer> labelOffsets;
    private List<String> linearization;
    private Map<String, Integer> blockIndices;

    public JVMCodegen(CFG cfg) {
        this.cfg = Objects.requireNonNull(cfg, "cfg cannot be null");
        this.bytecode = new ArrayList<>();
        this.labelOffsets = new HashMap<>();
    }

    /**
     * Generate complete bytecode from CFG.
     * This is the main entry point.
     */
    public List<String> generate() {
        // Step 1: Linearize blocks with fall-through optimization
        this.linearization = linearizeBlocksFallthrough();
        cfg.setLinearization(linearization);

        // Step 2: Build block index map for quick lookup
        this.blockIndices = new HashMap<>();
        for (int i = 0; i < linearization.size(); i++) {
            blockIndices.put(linearization.get(i), i);
        }

        // Step 3: Generate bytecode for each block
        for (String blockLabel : linearization) {
            IRBasicBlock block = cfg.getBlock(blockLabel);
            recordLabelOffset(blockLabel);
            generateBlock(block);
        }

        return Collections.unmodifiableList(bytecode);
    }

    /**
     * Linearize blocks with fall-through optimization for conditional jumps.
     *
     * Strategy:
     * - Use depth-first traversal starting from entry block
     * - When encountering a conditional jump, prioritize false-branch for fallthrough
     * - This makes the false-branch implicit, reducing jumps
     */
    private List<String> linearizeBlocksFallthrough() {
        List<String> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        dfs(cfg.getEntryLabel(), order, visited);

        // Ensure exit block is last
        if (!order.contains(cfg.getExitLabel())) {
            order.add(cfg.getExitLabel());
        }

        return order;
    }

    /**
     * Depth-first traversal with fall-through bias.
     * For conditional branches, false-branch is visited first (becomes fallthrough).
     */
    private void dfs(String label, List<String> order, Set<String> visited) {
        if (visited.contains(label)) {
            return;
        }

        visited.add(label);
        order.add(label);

        IRBasicBlock block = cfg.getBlock(label);
        if (block == null) {
            return;
        }

        Map<String, String> successors = block.getSuccessors();

        // For conditional jumps: false-branch first (becomes implicit fallthrough)
        if (successors.containsKey("false")) {
            dfs(successors.get("false"), order, visited);
            dfs(successors.get("true"), order, visited);
        } else if (successors.containsKey("default")) {
            // Unconditional jump
            dfs(successors.get("default"), order, visited);
        }
        // If no successors (return block), nothing to traverse
    }

    /**
     * Generate bytecode for a single basic block.
     * The key optimization happens here: detecting fall-through opportunities.
     */
    private void generateBlock(IRBasicBlock block) {
        List<IRInstruction> instructions = block.instructions();

        for (int i = 0; i < instructions.size(); i++) {
            IRInstruction instr = instructions.get(i);

            // Special handling for terminators (last instruction)
            if (i == instructions.size() - 1) {
                generateTerminator(block, instr);
            } else {
                // Regular instruction
                //instr.accept(this);
            }
        }
    }

    /**
     * Generate code for block terminators (jumps, returns, etc).
     * This is where fall-through optimization happens.
     */
    private void generateTerminator(IRBasicBlock block, IRInstruction instr) {
        if (instr instanceof IRInstruction.CondBranch condBranch) {
            generateOptimizedConditionalJump(block, condBranch);
        } else if (instr instanceof IRInstruction.Branch branch) {
            generateOptimizedUnconditionalJump(block, branch);
        } else {
            // Return or other terminal instruction
            //instr.accept(this);
        }
    }

    /**
     * Core optimization: emit conditional jump with fall-through awareness.
     *
     * Algorithm:
     * 1. Determine which label will be at the next position in linearization
     * 2. If it's the false-label, emit inverted jump (jump to true)
     * 3. If it's the true-label, emit normal jump (jump to false)
     * 4. If neither, emit both jumps (no optimization possible)
     */
    private void generateOptimizedConditionalJump(IRBasicBlock block, IRInstruction.CondBranch jump) {
        String trueLabel = jump.trueLabel();
        String falseLabel = block.getSuccessors().get("false");

        String nextLabel = getNextBlockLabel(block.label());

        if (nextLabel != null && nextLabel.equals(falseLabel)) {
            // FALSE is fall-through: emit inverted jump to true
            //emitConditionalJumpInverted(jump.condition(), trueLabel);
            // Implicitly fall through to false-label code

        } else if (nextLabel != null && nextLabel.equals(trueLabel)) {
            // TRUE is fall-through: emit jump to false (inverted condition)
            //emitConditionalJumpInverted(negateCondition(jump.condition()), falseLabel);
            // Implicitly fall through to true-label code

        } else {
            // Neither is fall-through: emit both jumps (no optimization)
            //emitConditionalJump(jump.condition(), trueLabel);
            emitUnconditionalJump(falseLabel);
        }
    }

    /**
     * Optimize unconditional jumps.
     * Only emit if the target is not the next block.
     */
    private void generateOptimizedUnconditionalJump(IRBasicBlock block, IRInstruction.Branch jump) {
        String target = jump.targetLabel();
        String nextLabel = getNextBlockLabel(block.label());

        if (!target.equals(nextLabel)) {
            emitUnconditionalJump(target);
        }
        // If target is next block, omit the jump (implicit fallthrough)
    }

    /**
     * Get the label of the block that will be linearized after the given block.
     */
    private String getNextBlockLabel(String currentLabel) {
        Integer currentIndex = blockIndices.get(currentLabel);
        if (currentIndex == null || currentIndex + 1 >= linearization.size()) {
            return null;
        }
        return linearization.get(currentIndex + 1);
    }

    /**
     * Emit a conditional jump with condition inversion.
     *
     * JVM instructions have built-in inversion:
     * - ifeq (jump if equal) = ifne inverted
     * - if_icmple (jump if <=) = if_icmpgt inverted
     * etc.
     */
    private void emitConditionalJumpInverted(String cond, String label) {
        String jvmInstr = toJVMCondJump(cond);
        emit(jvmInstr + " " + label + "  // conditional jump");
    }

    private void emitConditionalJump(String cond, String label) {
        String jvmInstr = toJVMCondJump(cond);
        emit(jvmInstr + " " + label);
    }

    private void emitUnconditionalJump(String label) {
        emit("goto " + label);
    }

    /**
     * Convert IR condition to JVM bytecode instruction.
     *
     * Examples:
     * - "x > 5" -> "if_icmpgt"
     * - "x == y" -> "if_icmpeq"
     * - "x != 0" -> "ifne"
     */
    private String toJVMCondJump(String cond) {
        // This is a simplified mapping; real implementation would be more sophisticated
        if (cond.contains(">")) return "if_icmpgt";
        if (cond.contains(">=")) return "if_icmpge";
        if (cond.contains("<")) return "if_icmplt";
        if (cond.contains("<=")) return "if_icmple";
        if (cond.contains("==")) return "if_icmpeq";
        if (cond.contains("!=")) return "if_icmpne";
        return "ifne";  // Default: boolean test
    }

    /**
     * Negate a condition for inverted jumps.
     * Used when true-label is the fallthrough target.
     */
    private String negateCondition(String cond) {
        // Simple pattern matching for common cases
        return cond.replace("==", "!=")
                .replace("!=", "==")
                .replace("<=", ">")
                .replace("<", ">=")
                .replace(">=", "<")
                .replace(">", "<=");
    }

    /**
     * Record bytecode offset for a label (for resolving jumps).
     */
    private void recordLabelOffset(String label) {
        labelOffsets.put(label, bytecode.size());
    }

    /**
     * Emit a bytecode instruction.
     */
    private void emit(String instruction) {
        bytecode.add(instruction);
    }

    // ============ Visitor Methods (for regular IR instructions) ============

    @Override
    public Void visitBinaryOp(IRInstruction.BinaryOp op) {
        /*
        emit(String.format("%s = %s %s %s",
                op.getDest(), op.left(), op.op(), op.right()));
        */
        return null;
    }

    @Override
    public Void visitConditionalJump(IRInstruction.CondBranch jump) {
        // Should be handled by generateTerminator
        emit("if " + jump.condition() + " goto " + jump.trueLabel());
        return null;
    }

    @Override
    public Void visitUnconditionalJump(IRInstruction.Branch jump) {
        // Should be handled by generateTerminator
        emitUnconditionalJump(jump.targetLabel());
        return null;
    }

    /*
    @Override
    public Void visitLabel(Label label) {
        // Labels are recorded at block start
        return null;
    }
    */

    @Override
    public Void visitFunctionCall(IRInstruction.Call call) {
        /*
        StringBuilder sb = new StringBuilder();
        if (call.getDest() != null) {
            sb.append(call.getDest()).append(" = ");
        }
        sb.append("call ").append(call.getFuncName()).append("(");
        String[] args = call.getArgs();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i]);
        }
        sb.append(")");
        emit(sb.toString());
        */
        return null;
    }

    @Override
    public Void visitReturn(IRInstruction.Return ret) {
        if (ret.value() != null) {
            emit("return " + ret.value());
        } else {
            emit("return");
        }
        return null;
    }

    /**
     * Get generated bytecode as a single string.
     */
    public String generateString() {
        return String.join("\n", generate());
    }
}