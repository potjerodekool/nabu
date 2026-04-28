package io.github.potjerodekool.nabu.ir;

import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;

public record IRField(Kind kind,
                      long flags,
                      String name,
                      IRType type,
                      IRValue value) {

    public static IRField recordComponent(String name,
                                          IRType type) {
        return new IRField(Kind.RECORD_COMPONENT, 0, name, type, null);
    }

    public static IRField field(long flags,
                                String name,
                                IRType type,
                                IRValue value) {
        return new IRField(Kind.FIELD, flags, name, type, value);
    }

    public enum Kind {
        FIELD,
        RECORD_COMPONENT
    }
}
