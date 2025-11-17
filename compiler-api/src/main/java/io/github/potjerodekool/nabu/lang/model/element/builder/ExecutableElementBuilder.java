package io.github.potjerodekool.nabu.lang.model.element.builder;

import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;

/**
 * A builder for building executable elements.
 *
 * @param <E> Type of ExecutableElement.
 */
public interface ExecutableElementBuilder<E extends ExecutableElement> extends ElementBuilder<ExecutableElementBuilder<E>> {

    ExecutableElementBuilder<E> parameter(VariableElement parameter);

    ExecutableElementBuilder<E> parameters(List<VariableElement> parameters);

    ExecutableElementBuilder<E> returnType(TypeMirror returnType);

    ExecutableElementBuilder<E> typeParameter(TypeParameterElement typeParameterElement);

    ExecutableElementBuilder<E> typeParameters(List<TypeParameterElement> typeParameterElements);

    ExecutableElementBuilder<E> thrownType(TypeMirror thrownType);

    ExecutableElementBuilder<E> thrownTypes(List<TypeMirror> thrownTypes);

    E build();
}
