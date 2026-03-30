package io.github.potjerodekool.nabu.compiler.backend.ir2;

import io.github.potjerodekool.nabu.ir.values.IRValue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bijhouder van lokale variabelen per methode-scope.
 *
 * Ondersteunt geneste scopes voor blokken (if, while, for, try).
 * Elke scope is een laag op een stack — bij lookup wordt van
 * binnenste naar buitenste scope gezocht.
 *
 * Gebruik:
 *   tracker.pushScope();                          // begin blok
 *   tracker.define("x", ptr);                     // lokale variabele
 *   Optional<IRValue> v = tracker.lookup("x");    // opzoeken
 *   tracker.popScope();                            // einde blok
 *
 * Wordt per methode gereset via reset().
 */
public class ScopeTracker {

    private final Deque<Map<String, IRValue>> scopes = new ArrayDeque<>();

    // -------------------------------------------------------
    // Scope-beheer
    // -------------------------------------------------------

    /**
     * Begin een nieuwe scope (bijv. bij een blok-statement).
     */
    public void pushScope() {
        scopes.push(new HashMap<>());
    }

    /**
     * Sluit de huidige scope.
     * @throws IllegalStateException als er geen scope open is.
     */
    public void popScope() {
        if (scopes.isEmpty())
            throw new IllegalStateException("Geen open scope om te sluiten");
        scopes.pop();
    }

    /**
     * Reset alle scopes — aanroepen bij het begin van elke methode.
     */
    public void reset() {
        scopes.clear();
    }

    // -------------------------------------------------------
    // Variabelen
    // -------------------------------------------------------

    /**
     * Registreert een variabele in de huidige (binnenste) scope.
     * De waarde is typisch een Alloca-pointer.
     *
     * @param name Variabelenaam.
     * @param ptr  IRValue — de pointer naar de variabele (Alloca).
     */
    public void define(String name, IRValue ptr) {
        if (scopes.isEmpty())
            throw new IllegalStateException(
                    "Geen open scope — roep pushScope() aan vóór define()");
        scopes.peek().put(name, ptr);
    }

    /**
     * Zoekt een variabele op van binnenste naar buitenste scope.
     *
     * @param name Variabelenaam.
     * @return De IRValue als gevonden, anders empty.
     */
    public Optional<IRValue> lookup(String name) {
        for (Map<String, IRValue> scope : scopes) {
            IRValue value = scope.get(name);
            if (value != null) return Optional.of(value);
        }
        return Optional.empty();
    }

    /**
     * Zoekt een variabele op en gooit een uitzondering als hij niet gevonden wordt.
     *
     * @param name Variabelenaam.
     * @return De IRValue.
     * @throws IllegalStateException als de variabele niet gevonden wordt.
     */
    public IRValue require(String name) {
        return lookup(name)
                .orElseThrow(() -> new IllegalStateException(
                        "Onbekende variabele: '" + name + "' — "
                                + "controleer of de variabele gedeclareerd is in de huidige scope"));
    }

    /**
     * Overschrijft een bestaande variabele in de scope waar hij gedefinieerd is.
     * Wordt gebruikt bij toewijzings-expressies (x = ...).
     *
     * @throws IllegalStateException als de variabele niet bestaat.
     */
    public void update(String name, IRValue ptr) {
        for (Map<String, IRValue> scope : scopes) {
            if (scope.containsKey(name)) {
                scope.put(name, ptr);
                return;
            }
        }
        throw new IllegalStateException(
                "Kan variabele niet updaten — niet gevonden: '" + name + "'");
    }

    /**
     * Geeft true als de variabele in een van de open scopes bestaat.
     */
    public boolean contains(String name) {
        return lookup(name).isPresent();
    }

    /**
     * Geeft het aantal open scopes terug (voor debugging).
     */
    public int depth() {
        return scopes.size();
    }
}
