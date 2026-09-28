package io.github.potjerodekool.nabu.backend.jvm;

import io.github.potjerodekool.nabu.backend.ir.types.IRType;

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
        final var key = normalized + "|" + typeKey(type);
        final var slot = nextSlot;
        slots.put(key, slot);
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
        final var kind = typeKey(type);
        final var key = normalized + "|" + kind;
        final var existing = slots.get(key);
        if (existing != null) {
            return existing;
        }

        final var slot = nextSlot;
        slots.put(key, slot);
        nextSlot += slotSize(type);
        return slot;
    }

    private static String typeKey(final IRType type) {
        if (type instanceof IRType.Int(int bits)) return "I" + bits;
        if (type instanceof IRType.Float(int bits)) return "F" + bits;
        if (type instanceof IRType.Bool) return "Z";
        if (type instanceof IRType.Ptr ptr) return "R" + typeToString(ptr.pointee());
        if (type instanceof IRType.Array arr) return "A" + typeToString(arr.elem());
        return "O" + type.toString();
    }

    private static String typeToString(final IRType type) {
        if (type == null) return "?";
        if (type instanceof IRType.Ptr ptr) return "ptr(" + typeToString(ptr.pointee()) + ")";
        return type.toString();
    }

    /**
     * Geeft het slot voor de gegeven naam; gooit een fout als het
     * slot nog niet toegekend is.
     */
    public int getSlot(final String name) {
        final var normalized = normalize(name);
        for (final var entry : slots.entrySet()) {
            if (entry.getKey().startsWith(normalized + "|")) {
                return entry.getValue();
            }
        }
        throw new IllegalStateException("Geen slot toegekend voor '" + name + "'");
    }

    public int getSlot(final String name, final IRType type) {
        final var normalized = normalize(name);
        final var key = normalized + "|" + typeKey(type);
        final var slot = slots.get(key);
        if (slot != null) {
            return slot;
        }
        throw new IllegalStateException("Geen slot toegekend voor '" + name + "' met type " + type);
    }

    /**
     * Zoekt een slot op zonder toe te kennen. Retourneert -1 als niet gevonden.
     */
    public int findSlot(final String name, final IRType type) {
        final var normalized = normalize(name);
        final var key = normalized + "|" + typeKey(type);
        final var slot = slots.get(key);
        return slot != null ? slot : -1;
    }

    public boolean hasSlot(final String name) {
        final var normalized = normalize(name);
        for (final var entry : slots.entrySet()) {
            if (entry.getKey().startsWith(normalized + "|")) {
                return true;
            }
        }
        return false;
    }

    /**
     * De genormaliseerde namen van alle toegekende slots.
     */
    public java.util.Set<String> slotNames() {
        final var result = new java.util.HashSet<String>();
        for (final var key : slots.keySet()) {
            final var idx = key.lastIndexOf('|');
            if (idx > 0) {
                result.add(key.substring(0, idx));
            } else {
                result.add(key);
            }
        }
        return java.util.Collections.unmodifiableSet(result);
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
