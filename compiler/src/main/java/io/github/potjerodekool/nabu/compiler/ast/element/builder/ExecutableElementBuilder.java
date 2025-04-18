package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.ExecutableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeParameterElement;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.List;
import java.util.function.Consumer;

public interface ExecutableElementBuilder<E extends ExecutableElement, EB extends ExecutableElementBuilder<E, EB>> extends ElementBuilder<ExecutableElement, EB> {

    EB parameter(Consumer<VariableElementBuilder<?>> consumer);

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
