package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.NestingKind;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeParameterElement;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.List;

public interface TypeSymbolBuilder <B extends TypeSymbolBuilder<B>> {

    B nestingKind(NestingKind nestingKind);

    B interfaceType(TypeMirror typeMirror);

    B outerType(TypeMirror outerType);

    B typeParameter(TypeParameterElement typeParameter);

    B typeParameters(List<TypeParameterElement> typeParameters);

    B enclosedElement(Element element);

    B superclass(TypeMirror superclass);

    TypeElement build();
    
    TypeElement buildError();
}
