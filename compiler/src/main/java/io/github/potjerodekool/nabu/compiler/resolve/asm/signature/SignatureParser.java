package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableClassType;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableType;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableTypeVariable;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class SignatureParser extends AbstractVisitor {

    private final List<MutableType> formalTypeParameters = new ArrayList<>();
    private MutableType superType;
    final List<MutableType> interfaceTypes = new ArrayList<>();
    private MutableType type;
    private final List<MutableType> parameterTypes = new ArrayList<>();
    private final List<MutableType> exceptionTypes = new ArrayList<>();
    private MutableType returnType;

    public SignatureParser(final int api,
                           final ClassElementLoader loader,
                           final ModuleSymbol moduleSymbol) {
        super(api, loader, null, moduleSymbol);
    }

    @Override
    public void visitFormalTypeParameter(final String name) {
        final var objectType = new MutableClassType(loadClass(Constants.OBJECT));
        formalTypeParameters.add(new MutableTypeVariable(name, objectType, null));
    }

    @Override
    public MutableType getType() {
        if (type != null) {
            return type;
        } else if (!formalTypeParameters.isEmpty()) {
            return formalTypeParameters.getLast();
        } else {
            return null;
        }
    }

    @Override
    public void setType(final MutableType type) {
        this.type = type;
    }

    public List<AbstractType> createFormalTypeParameters() {
        return formalTypeParameters.stream()
                .map(it -> (AbstractType) it.toType(loader, new HashMap<>()))
                .toList();
    }

    public TypeMirror createSuperType() {
        return superType.toType(loader, new HashMap<>());
    }

    public List<TypeMirror> createInterfaceTypes() {
        return interfaceTypes.stream()
                .map(it -> it.toType(loader, new HashMap<>()))
                .toList();
    }

    @Override
    public MutableType getSuperType() {
        return superType;
    }

    @Override
    protected void addParameterType(final MutableType type) {
        this.parameterTypes.add(type);
    }

    @Override
    public MutableType getLastParameterType() {
        return parameterTypes.getLast();
    }

    @Override
    public void replaceLastParameterType(final MutableType type) {
        parameterTypes.removeLast();
        parameterTypes.add(type);
    }

    protected MutableType getReturnType() {
        return returnType;
    }

    @Override
    public void setReturnType(final MutableType returnType) {
        this.returnType = returnType;
    }

    @Override
    protected void addExceptionType(final MutableType exceptionType) {
        this.exceptionTypes.add(exceptionType);
    }

    @Override
    protected void setSuperType(final MutableType type) {
        this.superType = type;
    }

    @Override
    protected void addInterfaceType(final MutableClassType type) {
        interfaceTypes.add(type);
    }

    public TypeMirror createFieldType() {
        return superType.toType(loader, new HashMap<>());
    }

    public MethodSignature createMethodSignature() {
        final var typeVariablesMap = new HashMap<String, TypeVariable>();

        final var typeParameters  = formalTypeParameters.stream()
                .map(fp -> (TypeVariable) fp.toType(loader, typeVariablesMap))
                .toList();

        final var paramTypes = parameterTypes.stream()
                .map(it -> it.toType(loader, typeVariablesMap))
                .toList();

        final var exceptionTypes = this.exceptionTypes.stream()
                .map(it -> it.toType(loader, typeVariablesMap))
                .toList();

        return new MethodSignature(
                typeParameters,
                returnType.toType(loader, typeVariablesMap),
                paramTypes,
                exceptionTypes
        );
    }
}
