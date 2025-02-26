package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public class IsSubTypeChecker {

    public static boolean isSubtype(final TypeMirror t1,
                                    final TypeMirror t2) {
        return isSubtype(t1, t2, true);
    }

    public static boolean isSubtypeNoCapture(final TypeMirror t1,
                                             final TypeMirror t2) {
        return isSubtype(t1, t2, true);
    }

    public static boolean isSubtype(final TypeMirror t1,
                                    final TypeMirror t2,
                                    final boolean capture) {
        if (t1.equals(t2)) {
            return true;
        }

        return false;
    }
}
