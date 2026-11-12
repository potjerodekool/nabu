package io.github.potjerodekool.nabu.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Conservatieve escape-analyse (analyse-only): bepaalt welke HeapAlloc-resultaten
 * de functie niet verlaten (niet teruggegeven, niet in een veld/array opgeslagen,
 * niet als argument aan een call doorgegeven, niet via MonitorEnter/Exit gebruikt
 * buiten de functie). Zulke allocaties zijn kandidaat voor scalar replacement
 * of stack-allocation. Deze pass transformeert nog niet; hij rapporteert alleen.
 */
public class EscapeAnalysis {

    public static final class Result {
        /** Temp-namen van HeapAlloc-resultaten die niet ontsnappen. */
        public final Set<String> nonEscaping = new HashSet<>();
        /** Totaal aantal HeapAllocs in de functie. */
        public int totalAllocs;
    }

    public static Result analyze(final IRFunction function) {
        return analyze(function, Map.of());
    }

    public static Result analyze(final IRFunction function, final Map<String, IRFunction> callees) {
        final Result result = new Result();

        // Verzamel alle HeapAlloc-resultaten.
        final Set<String> allocTemps = new HashSet<>();
        for (final IRBasicBlock b : function.blocks()) {
            for (final IRInstruction in : b.instructions()) {
                if (in instanceof IRInstruction.HeapAlloc ha) {
                    final String n = nameOf(ha.result());
                    if (n != null) {
                        allocTemps.add(n);
                        result.totalAllocs++;
                    }
                }
            }
        }

        // Een alloc ontsnapt als zijn temp ergens gebruikt wordt op een
        // ontsnappende manier. We scannen alle instructies.
        final Set<String> escaping = new HashSet<>();
        for (final IRBasicBlock b : function.blocks()) {
            for (final IRInstruction in : b.instructions()) {
                // Return van de alloc -> ontsnapt.
                if (in instanceof IRInstruction.Return ret && ret.value() != null) {
                    markEscapingIfAlloc(ret.value(), allocTemps, escaping);
                }
                // Store: ptr of value is alloc -> ontsnapt (opgeslagen in veld).
                if (in instanceof IRInstruction.Store st) {
                    markEscapingIfAlloc(st.ptr(), allocTemps, escaping);
                    markEscapingIfAlloc(st.value(), allocTemps, escaping);
                }
                // ArrayStore: array of value is alloc -> ontsnapt.
                if (in instanceof IRInstruction.ArrayStore as) {
                    markEscapingIfAlloc(as.array(), allocTemps, escaping);
                    markEscapingIfAlloc(as.value(), allocTemps, escaping);
                }
                // Call/IndirectCall: args die alloc zijn -> ontsnapt, tenzij de
                // callee bekend is en zijn parameters niet opslaat/retourneert.
                if (in instanceof IRInstruction.Call c) {
                    final IRFunction callee = callees.get(c.function());
                    for (int ai = 0; ai < c.args().size(); ai++) {
                        final IRValue a = c.args().get(ai);
                        if (callee != null && !storesOrReturnsParam(callee, ai)) {
                            // callee bewaart het argument niet -> geen escape via deze call
                            continue;
                        }
                        markEscapingIfAlloc(a, allocTemps, escaping);
                    }
                }
                if (in instanceof IRInstruction.IndirectCall ic) {
                    // onbekende callee: conservatief
                    for (final IRValue a : ic.args()) markEscapingIfAlloc(a, allocTemps, escaping);
                    markEscapingIfAlloc(ic.callee(), allocTemps, escaping);
                }
                // MonitorEnter/Exit op alloc: conservatief als ontsnappend beschouwen
                // (het object wordt aan de runtime doorgegeven).
                if (in instanceof IRInstruction.MonitorEnter me) markEscapingIfAlloc(me.object(), allocTemps, escaping);
                if (in instanceof IRInstruction.MonitorExit mx) markEscapingIfAlloc(mx.object(), allocTemps, escaping);
                // Throw van alloc -> ontsnapt.
                if (in instanceof IRInstruction.Throw thr && thr.result() != null) {
                    markEscapingIfAlloc(thr.result(), allocTemps, escaping);
                }
            }
        }

        for (final String a : allocTemps) {
            if (!escaping.contains(a)) result.nonEscaping.add(a);
        }
        return result;
    }

    private static void markEscapingIfAlloc(final IRValue v, final Set<String> allocTemps,
                                            final Set<String> escaping) {
        if (v == null) return;
        if (v instanceof IRValue.Temp t && allocTemps.contains(t.name())) {
            escaping.add(t.name());
        } else if (v instanceof IRValue.Values vals) {
            for (final IRValue inner : vals.values()) markEscapingIfAlloc(inner, allocTemps, escaping);
        }
    }

    /**
     * Of de callee zijn parameter op index {@code paramIndex} opslaat in een veld/array/global
     * of retourneert (en daarmee laat ontsnappen). Conservatief: bij twijfel true.
     */
    private static boolean storesOrReturnsParam(final IRFunction callee, final int paramIndex) {
        if (paramIndex >= callee.params.size()) return true;
        final IRValue param = callee.params.get(paramIndex);
        final String paramName = nameOf(param);
        if (paramName == null) return true;

        // Verzamel param-namen die ontsnappen (transitief via calls naar bekende callees
        // wordt hier niet gedaan; 1 niveau is genoeg voor de rapportage).
        for (final IRBasicBlock b : callee.blocks()) {
            for (final IRInstruction in : b.instructions()) {
                if (in instanceof IRInstruction.Return ret && ret.value() != null) {
                    if (namesValue(ret.value(), paramName)) return true;
                }
                if (in instanceof IRInstruction.Store st) {
                    if (namesValue(st.value(), paramName)) return true;
                }
                if (in instanceof IRInstruction.ArrayStore as) {
                    if (namesValue(as.value(), paramName)) return true;
                }
            }
        }
        return false;
    }

    private static boolean namesValue(final IRValue v, final String name) {
        if (v == null) return false;
        if (v instanceof IRValue.Temp t) return t.name().equals(name);
        if (v instanceof IRValue.Named n) return n.name().equals(name);
        if (v instanceof IRValue.Values vals) {
            for (final IRValue inner : vals.values()) if (namesValue(inner, name)) return true;
        }
        return false;
    }

    private static String nameOf(final IRValue v) {
        if (v instanceof IRValue.Temp t) return t.name();
        if (v instanceof IRValue.Named n) return n.name();
        return null;
    }
}