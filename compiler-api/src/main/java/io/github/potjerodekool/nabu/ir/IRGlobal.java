package io.github.potjerodekool.nabu.ir;

import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;

public record IRGlobal(
        String name,
        IRType type,
        IRValue initializer,
        Linkage linkage,
        boolean constant,
        IRType ownerType,
        boolean isStatic
) {
    public enum Linkage {
        INTERNAL,
        EXTERNAL,
        PRIVATE
    }

    public static IRGlobal mutable(final String name,
                                   final IRType type,
                                   final IRValue init,
                                   final IRType ownerType,
                                   final boolean isStatic) {
        return new IRGlobal(name, type, init, Linkage.EXTERNAL, false, ownerType, isStatic);
    }

    public static IRGlobal constant(String name, IRType type, IRValue init) {
        return new IRGlobal(name, type, init, Linkage.INTERNAL, true, null, false);
    }

    public static IRGlobal internal(String name, IRType type, IRValue init) {
        return new IRGlobal(name, type, init, Linkage.INTERNAL, false, null, false);
    }

    public static IRGlobal external(String name, IRType type) {
        return new IRGlobal(name, type, null, Linkage.EXTERNAL, false, null, false);
    }

    public static IRGlobal stringLiteral(String name, String value) {
        return new IRGlobal(name, new IRType.Ptr(IRType.I8),
                IRValue.ofString(value), Linkage.PRIVATE, true, null, false);
    }

    /**
     * Het pointertype naar deze global.
     */
    public IRType ptrType() {
        return new IRType.Ptr(type);
    }
}
