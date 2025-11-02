package io.github.potjerodekool.nabu.lang.model.element;

/**
 * An enumeration of element kinds.
 */
public enum ElementKind {
    PACKAGE,
    ENUM,
    CLASS,
    ANNOTATION_TYPE,
    INTERFACE,
    ENUM_CONSTANT,
    FIELD,
    PARAMETER,
    LOCAL_VARIABLE,
    EXCEPTION_PARAMETER,
    METHOD,
    CONSTRUCTOR,
    STATIC_INIT,
    INSTANCE_INIT,
    TYPE_PARAMETER,
    OTHER,
    RESOURCE_VARIABLE,
    MODULE,
    RECORD,
    RECORD_COMPONENT,
    BINDING_VARIABLE;

    public boolean isClass() {
        return this == CLASS || this == ENUM || this == RECORD;
    }

    public boolean isInterface() {
        return this == INTERFACE || this == ANNOTATION_TYPE;
    }

    public boolean isDeclaredType() {
        return isClass() || isInterface();
    }

    public boolean isField() {
        return this == FIELD || this == ENUM_CONSTANT;
    }

    public boolean isExecutable() {
        return this == METHOD
                || this == CONSTRUCTOR
                || this == STATIC_INIT
                || this == INSTANCE_INIT;
    }

    public boolean isInitializer( ){
        return this == STATIC_INIT || this == INSTANCE_INIT;
    }

    public boolean isVariable() {
        return switch (this) {
            case FIELD,
                 PARAMETER,
                 LOCAL_VARIABLE,
                 EXCEPTION_PARAMETER,
                 ENUM_CONSTANT,
                 RESOURCE_VARIABLE,
                 BINDING_VARIABLE -> true;
            default -> false;
        };
    }
}
