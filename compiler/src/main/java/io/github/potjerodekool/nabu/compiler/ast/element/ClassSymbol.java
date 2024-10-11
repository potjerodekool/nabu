package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.FileObject;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.ArrayList;
import java.util.List;

public class ClassSymbol extends TypeSymbol implements TypeElement {

    private String qualifiedName;

    private TypeMirror superType;

    private final List<TypeMirror> interfaces = new ArrayList<>();

    private FileObject fileObject;

    public ClassSymbol(final ElementKind kind,
                       final NestingKind nestingKind,
                       final String name,
                       final AbstractSymbol owner) {
        super(kind, name, owner);
    }

    private void resolveQualifiedName() {
        if (qualifiedName != null) {
            return;
        }

        final var enclosing = getEnclosingElement();

        if (enclosing != null) {
            final var enclosingName = enclosing instanceof QualifiedNameable qn
                    ? qn.getQualifiedName()
                    : enclosing.getSimpleName();
            qualifiedName = enclosingName + "." + getSimpleName();
        } else {
            qualifiedName = getSimpleName();
        }
    }

    @Override
    public void setEnclosingElement(final Element enclosingElement) {
        this.qualifiedName = null;
        super.setEnclosingElement(enclosingElement);
    }

    @Override
    public String getQualifiedName() {
        resolveQualifiedName();
        return qualifiedName;
    }

    public MethodSymbol findFunctionalMethod() {
        return getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.METHOD)
                .map(element -> (MethodSymbol) element)
                .findFirst()
                .orElse(null);
    }

    public TypeMirror getSuperType() {
        return superType;
    }

    public void setSuperType(final TypeMirror superType) {
        this.superType = superType;
    }

    public List<TypeMirror> getInterfaces() {
        return interfaces;
    }

    public void addInterface(final TypeMirror interfaceType) {
        this.interfaces.add(interfaceType);
    }

    public FileObject getFileObject() {
        return fileObject;
    }

    public void setFileObject(final FileObject fileObject) {
        this.fileObject = fileObject;
    }
}
