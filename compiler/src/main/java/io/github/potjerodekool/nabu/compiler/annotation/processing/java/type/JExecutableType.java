package io.github.potjerodekool.nabu.compiler.annotation.processing.java.type;

import javax.lang.model.type.*;
import java.util.List;

public class JExecutableType extends JAbstractType<io.github.potjerodekool.nabu.type.ExecutableType> implements ExecutableType {

    private TypeMirror returnType;
    private TypeMirror receiverType;
    private List<TypeVariable> typeVariables;
    private List<TypeMirror> parameterTypes;
    private List<TypeMirror> thrownTypes;

    public JExecutableType(final io.github.potjerodekool.nabu.type.ExecutableType original) {
        super(TypeKind.EXECUTABLE, original);
    }


    @Override
    public List<? extends TypeVariable> getTypeVariables() {
        if (typeVariables == null) {
            typeVariables = getOriginal().getTypeVariables().stream()
                    .map(TypeWrapperFactory::wrap)
                    .map(type -> (TypeVariable) type)
                    .toList();
        }

        return typeVariables;
    }

    @Override
    public TypeMirror getReturnType() {
        if (returnType == null) {
            returnType = TypeWrapperFactory.wrap(getOriginal().getReturnType());
        }
        return returnType;
    }

    @Override
    public List<? extends TypeMirror> getParameterTypes() {
        if (parameterTypes == null) {
            parameterTypes = getOriginal().getParameterTypes().stream()
                    .map(TypeWrapperFactory::wrap)
                    .toList();
        }

        return parameterTypes;
    }

    @Override
    public TypeMirror getReceiverType() {
        if (receiverType == null) {
            receiverType = TypeWrapperFactory.wrap(getOriginal().getReceiverType());
        }
        return receiverType;
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
    public <R, P> R accept(final TypeVisitor<R, P> v, final P p) {
        return v.visitExecutable(this, p);
    }
}
