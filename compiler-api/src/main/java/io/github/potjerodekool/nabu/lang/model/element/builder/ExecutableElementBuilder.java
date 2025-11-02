package io.github.potjerodekool.nabu.lang.model.element.builder;

import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;
import java.util.function.Consumer;

/**
 * A builder for building executable elements.
 * @param <E> Type of ExecutableElement.
 * @param <EB> Type of ExecutableElementBuilder.
 */
public interface ExecutableElementBuilder<E extends ExecutableElement, EB extends ExecutableElementBuilder<E, EB>> extends ElementBuilder<EB> {

    EB parameter(Consumer<VariableElementBuilder<VariableElement>> consumer);

    EB returnType(TypeMirror returnType);

    EB typeParameter(TypeParameterElement typeParameterElement);

    EB typeParameters(List<TypeParameterElement> typeParameterElements);

    EB argumentType(TypeMirror argumentType);

    EB argumentTypes(List<TypeMirror> argumentTypes);

    EB argumentTypes(TypeMirror... argumentTypes);

    EB thrownType(TypeMirror thrownType);

    EB thrownTypes(List<TypeMirror> thrownTypes);

    ExecutableElement build();
}
