package io.github.potjerodekool.nabu.compiler.backend.ir.optimize;

import io.github.potjerodekool.nabu.compiler.ir.IRFunction;

/**
 * Interface voor een enkele optimalisatiepass op een IR-functie.
 */
@FunctionalInterface
public interface OptimizationPass {

    /**
     * Voert de optimalisatie uit op de gegeven functie.
     *
     * @param function de te optimaliseren functie
     * @return {@code true} als de functie is gewijzigd
     */
    boolean run(IRFunction function);
}
