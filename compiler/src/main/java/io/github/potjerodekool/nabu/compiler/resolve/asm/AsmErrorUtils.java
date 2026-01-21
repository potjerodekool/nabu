package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ErrorSymbol;
import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;

import java.util.Objects;

public final class AsmErrorUtils {

    private AsmErrorUtils() {
    }

    /**
     * TODO Temporary fix, {@link io.github.potjerodekool.nabu.resolve.ClassElementLoader#loadClass(ModuleElement, String)}
     * should return a ErrorSymbol if class wasn't found.
     * After that is fixed this method should be deleted.
     */
    public static TypeElement typeOrError(final TypeElement typeElement) {
        return Objects.requireNonNullElseGet(typeElement, () -> new ErrorSymbol("error"));
    }
}
