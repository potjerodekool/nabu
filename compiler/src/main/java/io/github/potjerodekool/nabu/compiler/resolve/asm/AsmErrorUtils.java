package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ErrorSymbol;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;

import java.util.Objects;

public final class AsmErrorUtils {

    private AsmErrorUtils() {
    }

    /**
     * Retourneert het gegeven TypeElement, of een ErrorSymbol als het null is.
     * Dit is een tijdelijke oplossing totdat ClassElementLoader.loadClass()
     * standaard een ErrorSymbol retourneert bij het niet vinden van een klasse.
     */
    public static TypeElement typeOrError(final TypeElement typeElement) {
        return Objects.requireNonNullElseGet(typeElement, () -> new ErrorSymbol("error"));
    }
}
