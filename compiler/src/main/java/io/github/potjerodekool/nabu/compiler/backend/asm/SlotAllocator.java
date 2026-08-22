package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.compiler.ir.types.IRType;

import java.util.HashMap;
import java.util.Map;

/**
 * Kent aan elke SSA-temp een unieke JVM-local-variable-slot toe.
 *
 * Het adresseren gebeurt volledig op naam: elke temp-naam (bijvoorbeeld
 * "%phi.while.cond.sum.ptr.while.cond") krijgt exact één slot toegewezen.
 * Parameters worden eerst toegekend (slot 0 = "this" voor instantie-methoden),
 * daarna worden tijdelijke waarden luierg toegekend bij eerste referentie.
 */
public final class SlotAllocator {

    private final Map<String, Integer> slots = new HashMap<>();
    private int nextSlot = 0;

    /**
     * Kent een slot toe aan een parameter (vóór alle tijdelijke waarden).
     *
     * @param name parameter-naam (bijvoorbeeld "%arg0" of "%this")
     * @param type parametertype
     * @return het toegekende slot-nummer
     */
    public int allocateParam(final String name,
                             final IRType type) {
        final var normalized = normalize(name);
        final var slot = nextSlot;
        slots.put(normalized, slot);
        nextSlot += slotSize(type);
        return slot;
    }

    /**
     * Geeft het slot voor de gegeven naam; kent een nieuw slot toe
     * bij eerste referentie.
     */
    public int slotOf(final String name,
                      final IRType type) {
        final var normalized = normalize(name);
        final var existing = slots.get(normalized);
        if (existing != null) {
            return existing;
        }

        final var slot = nextSlot;
        slots.put(normalized, slot);
        nextSlot += slotSize(type);
        return slot;
    }

    /**
     * Geeft het slot voor de gegeven naam; gooit een fout als het
     * slot nog niet toegekend is.
     */
    public int getSlot(final String name) {
        final var slot = slots.get(normalize(name));
        if (slot == null) {
            throw new IllegalStateException("Geen slot toegekend voor '" + name + "'");
        }
        return slot;
    }

    public boolean hasSlot(final String name) {
        return slots.containsKey(normalize(name));
    }

    /**
     * Aantal gebruikte slots (inclusief categorie-2 slots die 2 nemen).
     */
    public int size() {
        return nextSlot;
    }

    /**
     * Aantal JVM-slots dat een waarde van dit type inneemt
     * (long en double nemen 2 slots in).
     */
    public static int slotSize(final IRType type) {
        if (type instanceof IRType.Float(int bits1) && bits1 == 64) {
            return 2;
        }
        if (type instanceof IRType.Int(int bits) && bits == 64) {
            return 2;
        }
        return 1;
    }

    /**
     * Normaliseert een naam door een eventueel voorafgaand '%'-teken te
     * verwijderen, zodat "%arg0" en "arg0" naar hetzelfde slot wijzen.
     */
    public static String normalize(final String name) {
        if (name == null) {
            return null;
        }
        return name.startsWith("%") ? name.substring(1) : name;
    }
}
