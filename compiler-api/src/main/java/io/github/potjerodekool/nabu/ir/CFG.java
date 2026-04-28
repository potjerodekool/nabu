package io.github.potjerodekool.nabu.ir;

import java.util.*;

/**
 * Control Flow Graph (CFG): directed graph of basic blocks.
 *
 * This representation is fundamental for:
 * - Data flow analysis
 * - Optimization (constant folding, dead code elimination)
 * - Code generation (layout, register allocation)
 */
public class CFG {
    private final Map<String, IRBasicBlock> blocks;
    private final String entryLabel;
    private final String exitLabel;
    private List<String> blockOrder;  // linearization cache

    public CFG(String entryLabel, String exitLabel) {
        this.entryLabel = Objects.requireNonNull(entryLabel);
        this.exitLabel = Objects.requireNonNull(exitLabel);
        this.blocks = new LinkedHashMap<>();  // preserve insertion order
        this.blockOrder = null;
    }

    public void addBlock(final IRBasicBlock block) {
        this.blocks.put(block.label(), block);
    }

    public void addBlock(final List<IRBasicBlock> blocks) {
        for (IRBasicBlock block : blocks) {
            this.blocks.put(block.label(), block);
        }
    }

    public IRBasicBlock getOrCreateBlock(String label) {
        return blocks.computeIfAbsent(label, IRBasicBlock::new);
    }

    public IRBasicBlock getBlock(String label) {
        return blocks.get(label);
    }

    public Collection<IRBasicBlock> getAllBlocks() {
        return Collections.unmodifiableCollection(blocks.values());
    }

    public String getEntryLabel() { return entryLabel; }
    public String getExitLabel() { return exitLabel; }

    /**
     * Get predecessors of a block.
     */
    public Set<String> getPredecessors(String label) {
        Set<String> preds = new HashSet<>();
        for (IRBasicBlock block : blocks.values()) {
            if (block.getSuccessors().containsValue(label)) {
                preds.add(block.label());
            }
        }
        return preds;
    }

    /**
     * Invalidate blockOrder cache when CFG changes.
     */
    public void invalidateLinearization() {
        this.blockOrder = null;
    }

    /**
     * Get cached linearization, or return null if not computed.
     */
    public List<String> getLinearization() {
        return blockOrder;
    }

    /**
     * Cache the linearization order.
     */
    public void setLinearization(List<String> order) {
        this.blockOrder = order;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Control Flow Graph ===\n");
        for (IRBasicBlock block : blocks.values()) {
            sb.append(block);
            for (Map.Entry<String, String> successor : block.getSuccessors().entrySet()) {
                sb.append("  -> [").append(successor.getKey()).append("] ").append(successor.getValue()).append("\n");
            }
        }
        return sb.toString();
    }
}