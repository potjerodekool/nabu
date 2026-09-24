package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.types.IRType;

import java.util.List;

/**
 * Overload-sleutels voor LLVM-functiedeclaraties.
 *
 * De IR gebruikt `Owner_methode`-namen zonder descriptor, waardoor
 * overloaded JVM-methoden (bv. `Range.min()` en `Range.min(int)`) met de
 * zelfde IR-functienaam terechtkomen. Zonder aparte sleutel deelt LLVM
 * AddFunction de EERSTGEdelareerde signature, waardoor GetParam(i) van
 * een latere overload een garbage-pointer oplevert (jniLLVM-crash).
 *
 * De sleutel is `<fnName>#<paramdesc>`; de param-desc bevat een 'P'
 * slot als de callee impliciete 'this' draagt (callKind != STATIC of
 * callee-side IRFunction params met %this). #
 * '.' is niet te gebruiken (legaal in LLVM-id's), `#` wel uniek.
 */
final class FnKeys {

    private FnKeys() {
    }

    /** Levert de canonieke overload-sleutel: `<fnName>#<types>` */
    static String fnKey(final String fnName, final List<IRType> paramTypes) {
        final var sb = new StringBuilder(fnName).append('#');
        if (paramTypes != null) {
            for (final var t : paramTypes) {
                sb.append(typeKey(t));
            }
        }
        return sb.toString();
    }

    private static String typeKey(final IRType t) {
        if (t == null) {
            return "O";
        }
        if (t instanceof IRType.Int i) {
            return "I" + i.bits();
        }
        if (t instanceof IRType.Float f) {
            return "F" + f.bits();
        }
        if (t instanceof IRType.Bool) {
            return "Z";
        }
        if (t instanceof IRType.Void) {
            return "V";
        }
        if (t instanceof IRType.Ptr) {
            return "P";
        }
        if (t instanceof IRType.Array a) {
            return "[" + typeKey(a.elem()) + "]";
        }
        return "O";
    }
}
