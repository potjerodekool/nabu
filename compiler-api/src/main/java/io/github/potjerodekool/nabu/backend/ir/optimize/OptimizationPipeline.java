package io.github.potjerodekool.nabu.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;

import java.util.ArrayList;
import java.util.List;

/**
 * Optimalisatiepijplijn: voert een reeks passes uit op elke functie
 * en herhaalt tot geen pass meer wijzigingen aanbrengt (fixpoint).
 */
public class OptimizationPipeline {

    private final List<OptimizationPass> passes;
    private final int maxIterations;

    public OptimizationPipeline(final List<OptimizationPass> passes) {
        this(passes, 10);
    }

    public OptimizationPipeline(final List<OptimizationPass> passes,
                                final int maxIterations) {
        this.passes = List.copyOf(passes);
        this.maxIterations = maxIterations;
    }

    /**
     * Voert de pijplijn uit op alle functies in de module.
     */
    public IRModule run(final IRModule module) {
        final var optimizedFunctions = module.functions().stream()
                .map(this::run)
                .toList();
        return module.withFunctions(optimizedFunctions);
    }

    /**
     * Voert de pijplijn uit op één functie tot fixpoint.
     */
    public IRFunction run(final IRFunction function) {
        if (function.isExternal()) {
            return function;
        }

        for (int iter = 0; iter < maxIterations; iter++) {
            boolean changed = false;

            for (final var pass : passes) {
                changed |= pass.run(function);
            }

            if (!changed) {
                break;
            }
        }

        return function;
    }

    /**
     * Maakt een standaardpijplijn met alle beschikbare passes.
     * Volgorde: TypeInference → ConstantFolder → CopyPropagation → GVN → DCE
     */
    public static OptimizationPipeline defaultPipeline() {
        final List<OptimizationPass> passes = new ArrayList<>();
        // BISECT: passe-per-passe herinschakelen; alle vijf breken de
        // slot-gebaseerde emissie (picocli CommandLine.copy() uninit).
        // passes.add(new TypeInference());
        passes.add(new ConstantFolder());
        passes.add(new CopyPropagation());
        passes.add(new GlobalValueNumbering());
        passes.add(new DeadCodeElimination());
        return new OptimizationPipeline(passes);
    }
}
