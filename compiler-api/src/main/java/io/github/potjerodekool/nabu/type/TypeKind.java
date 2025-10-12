package io.github.potjerodekool.nabu.type;

public enum TypeKind {
    VOID(false),
    BOOLEAN(true),
    CHAR(true),
    BYTE(true),
    SHORT(true),
    INT(true),
    FLOAT(true),
    LONG(true),
    DOUBLE(true),
    ARRAY(false),
    DECLARED(false),
    EXECUTABLE(false),
    NULL(false),
    WILDCARD(false),
    INTERSECTION(false),
    UNION(false),
    NONE(false),
    TYPEVAR(false),
    PACKAGE(false),
    MODULE(false),
    ERROR(false);

    final boolean isPrimitive;

    TypeKind(final boolean isPrimitive) {
        this.isPrimitive = isPrimitive;
    }

    public boolean isPrimitive() {
        return isPrimitive;
    }
}
