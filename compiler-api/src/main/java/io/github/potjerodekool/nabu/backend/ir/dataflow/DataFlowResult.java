package io.github.potjerodekool.nabu.backend.ir.dataflow;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;

import java.util.Map;

/**
 * Resultaat van een dataflow-analyse: IN- en OUT-waarden per blok.
 */
public record DataFlowResult<V>(Map<String, V> in, Map<String, V> out) {

    public V in(IRBasicBlock block) {
        return in.get(block.label());
    }

    public V out(IRBasicBlock block) {
        return out.get(block.label());
    }

    public V in(String label) {
        return in.get(label);
    }

    public V out(String label) {
        return out.get(label);
    }
}