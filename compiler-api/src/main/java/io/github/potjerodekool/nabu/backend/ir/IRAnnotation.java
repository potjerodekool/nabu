package io.github.potjerodekool.nabu.backend.ir;

import java.util.List;
import java.util.Map;

/**
 * Een runtime-zichtbare annotatie van een klasse of veld uit bytecode (de
 * JDK of een library), voor de reflectie-metadata van een native backend.
 * Annotaties uit broncode staan als {@code CompoundAttribute} in het IR.
 *
 * @param typeInternalName het annotatietype, bv. {@code picocli/CommandLine$Option}.
 * @param values           elementnaam naar waarde, in de vorm van ASM's
 *                         {@code AnnotationNode}: String, een primitieve
 *                         wrapper, {@code org.objectweb.asm.Type} voor een
 *                         klasse, {@code String[] {descriptor, naam}} voor
 *                         een enum-constante, {@link IRAnnotation} voor een
 *                         geneste annotatie en {@link List} voor een array.
 */
public record IRAnnotation(String typeInternalName, Map<String, Object> values) {

    public IRAnnotation {
        values = Map.copyOf(values);
    }
}
