package io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.TypeSymbol;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.lang.model.element.NestingKind;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MutableClassType extends MutableType {

    private final TypeSymbol element;
    private final MutableClassType outerType;
    private final List<MutableType> typeArguments = new ArrayList<>();

    public MutableClassType(final TypeSymbol element) {
        this(element, null);
    }

    public MutableClassType(final TypeSymbol element,
                            final MutableClassType outerType) {
        var outer = outerType;

        if (element instanceof ClassSymbol classSymbol) {
            if (classSymbol.getNestingKind() != NestingKind.TOP_LEVEL) {
                outer = new MutableClassType(
                        (TypeSymbol) classSymbol.getEnclosingElement()
                );
            }
        }
        this.element = element;
        this.outerType = outer;
    }

    @Override
    public TypeMirror toType(final ClassElementLoader loader, final Map<String, TypeVariable> typeVariablesMap) {
        final var typeArgs = typeArguments.stream()
                .map(it -> (AbstractType) it.toType(loader, typeVariablesMap))
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

    public TypeSymbol getElement() {
        return element;
    }

    public void addTypeArgument(final MutableType typeArg) {
        this.typeArguments.add(typeArg);
    }

    public String getClassName() {
        return element.getQualifiedName();
    }
}
