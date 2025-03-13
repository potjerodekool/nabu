package io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVariable;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MutableClassType extends MutableType {

    private final TypeElement element;
    private final MutableClassType outerType;
    private final List<MutableType> typeArguments = new ArrayList<>();

    public MutableClassType(final TypeElement element) {
        this(element, null);
    }

    public MutableClassType(final TypeElement element,
                            final MutableClassType outerType) {
        this.element = element;
        this.outerType = outerType;
    }

    @Override
    public TypeMirror toType(final ClassElementLoader loader, final Map<String, TypeVariable> typeVariablesMap) {
        final var typeArgs = typeArguments.stream()
                .map(it -> it.toType(loader, typeVariablesMap))
                .toList();

        final var outerType = this.outerType != null
                ? this.outerType.toType(loader, typeVariablesMap)
                : null;

        return new CClassType(
                outerType,
                element,
                typeArgs
        );
    }

    public void addTypeArgument(final MutableType typeArg) {
        this.typeArguments.add(typeArg);
    }

    public String getClassName() {
        return element.getQualifiedName();
    }
}
