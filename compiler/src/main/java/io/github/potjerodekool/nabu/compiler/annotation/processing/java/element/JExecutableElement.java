package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.TypeWrapperFactory;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.tools.TodoException;

import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class JExecutableElement extends JElement<ExecutableElement> implements javax.lang.model.element.ExecutableElement {

    private TypeMirror returnType;
    private TypeMirror receiverType;
    private List<TypeMirror> thrownTypes;
    private AnnotationValue defaultValue;
    private List<TypeParameterElement> typeParameters;
    private List<VariableElement> parameters;

    protected JExecutableElement(final ExecutableElement original) {
        super(original);
    }

    @Override
    public List<? extends TypeParameterElement> getTypeParameters() {
        if (typeParameters == null) {
            typeParameters = getOriginal().getTypeParameters().stream()
                    .map(ElementWrapperFactory::wrap)
                    .map(typeParameterElement -> (TypeParameterElement) typeParameterElement)
                    .toList();
        }

        return typeParameters;
    }

    @Override
    public TypeMirror getReturnType() {
        if (returnType == null) {
            returnType = TypeWrapperFactory.wrap(getOriginal().getReturnType());
        }
        return returnType;
    }

    @Override
    public List<? extends VariableElement> getParameters() {
        if (parameters == null) {
            parameters = getOriginal().getParameters().stream()
                    .map(ElementWrapperFactory::wrap)
                    .map(parameterElement -> (VariableElement) parameterElement)
                    .toList();
        }

        return parameters;
    }

    @Override
    public TypeMirror getReceiverType() {
        if (receiverType == null) {
            receiverType = TypeWrapperFactory.wrap(getOriginal().getReceiverType());
        }
        return receiverType;
    }

    @Override
    public boolean isVarArgs() {
        return getOriginal().isVarArgs();
    }

    @Override
    public boolean isDefault() {
        return getOriginal().isDefault();
    }

    @Override
    public List<? extends TypeMirror> getThrownTypes() {
        if (thrownTypes == null) {
            thrownTypes = getOriginal().getThrownTypes().stream()
                    .map(TypeWrapperFactory::wrap)
                    .toList();
        }

        return thrownTypes;
    }

    @Override
    public AnnotationValue getDefaultValue() {
        if (defaultValue == null) {
            defaultValue = ElementWrapperFactory.wrap(getOriginal().getDefaultValue());
        }
        return defaultValue;
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitExecutable(this, p);
    }

    @Override
    public String toString() {
        final var simpleName = getOriginal().getSimpleName();
        final var params = getParameters().stream()
                .map(param -> {
                    final var type = param.asType().toString();
                    final var paramName = param.getSimpleName();
                    return type + " " + paramName;
                }).collect(Collectors.joining(", ", "(", ")"));

        return simpleName + " " + params;
    }
}
