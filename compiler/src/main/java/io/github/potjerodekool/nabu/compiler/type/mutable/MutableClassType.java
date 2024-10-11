package io.github.potjerodekool.nabu.compiler.type.mutable;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.ArrayList;
import java.util.List;

public class MutableClassType implements ClassType {

    private Element element;

    private ClassType outerType;

    private List<TypeMirror> parameterTypes;

    public MutableClassType(final Element classSymbol) {
        this(classSymbol, null);
    }

    public MutableClassType(final Element classSymbol,
                            final List<TypeMirror> parameterTypes) {
        this(null, classSymbol, parameterTypes);
    }

    public MutableClassType(final ClassType outerType,
                            final Element classSymbol,
                            final List<TypeMirror> parameterTypes) {
        this.outerType = outerType;
        this.element = classSymbol;
        if (parameterTypes != null
                && !parameterTypes.isEmpty()) {
            this.parameterTypes = new ArrayList<>(parameterTypes);
        }
    }

    @Override
    public Element asElement() {
        return element;
    }

    public void addParameterType(final TypeMirror parameterType) {
        if (this.parameterTypes == null) {
            this.parameterTypes = new ArrayList<>();
        }
        this.parameterTypes.add(parameterType);
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.REFERENCE;
    }

    @Override
    public ClassType getOuterType() {
        return outerType;
    }

    public void setOuterType(final ClassType outerType) {
        this.outerType = outerType;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor,
                           final P param) {
        return visitor.visitClassType(this, param);
    }

    @Override
    public List<TypeMirror> getParameterTypes() {
        return parameterTypes;
    }

    public void toInnerClassType(final ClassType innerType) {
        final var outerClassType = new MutableClassType(
                this.outerType,
                element,
                parameterTypes
        );

        this.outerType = outerClassType;
        this.element = innerType.asElement();
        this.parameterTypes = innerType.getParameterTypes();
    }
}
