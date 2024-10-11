package io.github.potjerodekool.nabu.compiler.type;

import io.github.potjerodekool.nabu.compiler.ast.element.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;

import java.util.List;

public interface MethodType extends TypeMirror {

    TypeElement getOwner();

    MethodSymbol getMethodSymbol();

    List<TypeMirror> getArgumentTypes();

    TypeMirror getReturnType();
}
