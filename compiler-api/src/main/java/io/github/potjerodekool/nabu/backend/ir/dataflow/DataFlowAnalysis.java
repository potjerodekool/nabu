package io.github.potjerodekool.nabu.backend.ir.dataflow;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;

/**
 * Interface voor een dataflow-analyse op een IR-functie.
 *
 * @param <V> het type van de dataflow-waarde per blok
 */
public interface DataFlowAnalysis<V> {

    /** Richting van de analyse. */
    DataFlowDirection direction();

    /** Waarde aan de boundary (entry voor forward, exit voor backward). */
    V boundaryValue(IRFunction function);

    /** Initiële waarde voor niet-boundary blokken. */
    V initialValue();

    /** Transfer-functie: bereken OUT (forward) of IN (backward) uit IN/OUT. */
    V transfer(IRBasicBlock block, V in);

    /** Combineer twee waarden (meet/join). */
    V meet(V a, V b);

    /** Controleert of twee waarden gelijk zijn (voor fixpoint). */
    boolean equalsValue(V a, V b);

    /**
     * Verfijn de waarde {@code v} gegeven een conditie {@code condition} die
     * in de richting {@code truthValue} (true/false) is geëvalueerd.
     * Standaard: geen verfijning.
     */
    default V refine(V v, io.github.potjerodekool.nabu.backend.ir.IRBasicBlock predecessorBlock,
                     io.github.potjerodekool.nabu.backend.ir.values.IRValue condition,
                     boolean truthValue) {
        return v;
    }

    /**
     * Widening-operator voor loops: combineer twee waarden zodat de
     * analyse convergeert. Standaard: meet (conservatief).
     */
    default V widen(V a, V b) {
        return meet(a, b);
    }
}