package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.TypeSymbol;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.type.ErrorType;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;

public abstract class AbstractType implements TypeMirror {

    private static final ThreadLocal<java.util.Set<TypeMirror>> TO_STRING_GUARD =
            ThreadLocal.withInitial(() ->
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

    public static final class ToStringGuard {
        private final TypeMirror type;
        private final boolean active;

        private ToStringGuard(final TypeMirror type) {
            this.type = type;
            this.active = TO_STRING_GUARD.get().add(type);
        }

        public boolean isActive() {
            return active;
        }

        public void close() {
            if (active) {
                TO_STRING_GUARD.get().remove(type);
            }
        }
    }

    public static ToStringGuard enterToString(final TypeMirror type) {
        return new ToStringGuard(type);
    }

    public static final CNoType noType = new CNoType() {
        @Override
        public String toString() {
            return "none";
        }
    };

    protected TypeSymbol element;

    protected AbstractType(final TypeSymbol typeElement) {
        setElement(typeElement);
    }

    public void setElement(final TypeSymbol element) {
        this.element = element;
    }

    @Override
    public abstract boolean equals(Object obj);

    @Override
    public abstract int hashCode();

    @Override
    public List<TypeMirror> getTypeArguments() {
        return List.of();
    }

    public Element asElement() {
        return element;
    }

    public boolean isError() {
        if (this instanceof ErrorType) {
            return true;
        }

        return element.isError();
    }

    public boolean hasErasedSupertypes() {
        return isRaw();
    }

    public <Z> AbstractType map(final TypeMapping<Z> mapping) {
        return mapping.visit(this, null);
    }

}
