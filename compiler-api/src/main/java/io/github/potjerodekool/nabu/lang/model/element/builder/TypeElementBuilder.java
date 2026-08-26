package io.github.potjerodekool.nabu.lang.model.element.builder;

import io.github.potjerodekool.nabu.lang.model.element.NestingKind;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;

public interface TypeElementBuilder<T extends TypeElement> extends ElementBuilder<TypeElementBuilder<T>> {

    @Override
    T build();

    TypeElementBuilder<T> nestingKind(NestingKind nestingKind);

    T buildError();

    TypeElementBuilder<T> superclass(TypeMirror objectType);

    TypeElementBuilder<T> typeParameter(TypeParameterElement typeParameterElement);

    TypeElementBuilder<T> typeParameters(List<? extends TypeParameterElement> element);

    TypeElementBuilder<T> outerType(DeclaredType outerType);
}
