package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeParameterElement;
import io.github.potjerodekool.nabu.compiler.ast.symbol.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.List;

public interface MethodSymbolBuilder<B extends MethodSymbolBuilder<B>> {

    B parameter(VariableSymbol parameter);

    B returnType(final TypeMirror returnType);

    B typeParameter(final TypeParameterElement typeParameterElement);

    B typeParameters(final List<TypeParameterElement> typeParameterElements);

    B argumentType(final TypeMirror argumentType);

    B argumentTypes(final List<TypeMirror> argumentTypes);

    B argumentTypes(final TypeMirror... argumentTypes);

    B thrownType(final TypeMirror thrownType);

    B thrownTypes(final List<TypeMirror> thrownTypes);

    MethodSymbol build();

    B receiverType(TypeMirror receiverType);
}
