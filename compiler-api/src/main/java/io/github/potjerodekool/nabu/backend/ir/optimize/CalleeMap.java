package io.github.potjerodekool.nabu.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Callees by IR name. Overloads share one IR name (the emitter tells them apart by their parameter types), so a name
 * that more than one function carries is not in the map itself; {@link #resolve} picks the overload of a call by the
 * parameter types of the call, and only when exactly one matches.
 */
public final class CalleeMap extends HashMap<String, IRFunction> {

    private final Map<String, List<IRFunction>> overloads = new HashMap<>();

    /** Adds a function; a second function of the same name makes the name an overload set. */
    public void add(final IRFunction f) {
        final List<IRFunction> list = overloads.computeIfAbsent(f.name, k -> new ArrayList<>(1));
        list.add(f);
        if (list.size() == 1) {
            put(f.name, f);
        } else {
            remove(f.name);
        }
    }

    /** Removes a function by name, also from the overload sets (a name that the passes must not know). */
    public void forget(final String name) {
        remove(name);
        overloads.remove(name);
    }

    /** The function that {@code call} calls, or null when it is not known for certain. */
    public IRFunction resolve(final IRInstruction.Call call) {
        final IRFunction unique = get(call.function());
        if (unique != null) {
            return unique;
        }
        final List<IRFunction> candidates = overloads.get(call.function());
        if (candidates == null || call.paramTypes() == null) {
            return null;
        }
        IRFunction found = null;
        for (final IRFunction f : candidates) {
            if (matches(f, call.paramTypes())) {
                if (found != null) {
                    return null;
                }
                found = f;
            }
        }
        return found;
    }

    /** The overload set of a name when the name is shared (for the passes that look a callee up by name). */
    public static IRFunction resolve(final Map<String, IRFunction> callees, final IRInstruction.Call call) {
        return callees instanceof CalleeMap m ? m.resolve(call) : callees.get(call.function());
    }

    private static boolean matches(final IRFunction f, final List<IRType> types) {
        // The receiver of a non-static callee is a parameter of the function and may or may not be in the call's types.
        final int offset = f.params.size() - types.size();
        if (offset < 0 || offset > 1) {
            return false;
        }
        for (int i = 0; i < types.size(); i++) {
            if (!same(f.params.get(i + offset).type(), types.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean same(final IRType a, final IRType b) {
        if (a instanceof IRType.Ptr pa && b instanceof IRType.Ptr pb) {
            // Without a descriptor on both sides a match is a guess (the call may be to an overload that has no body).
            return pa.jvmDescriptor() != null && pa.jvmDescriptor().equals(pb.jvmDescriptor());
        }
        return a.equals(b);
    }
}
