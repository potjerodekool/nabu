package io.github.potjerodekool.nabu.compiler.ast.symbol.impl;

import io.github.potjerodekool.nabu.compiler.type.impl.CPackageType;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ElementVisitor;
import io.github.potjerodekool.nabu.lang.model.element.PackageElement;
import io.github.potjerodekool.nabu.resolve.scope.WritableScope;

public class PackageSymbol extends TypeSymbol implements PackageElement {

    public static final PackageSymbol UNNAMED_PACKAGE = new PackageSymbol(
            null,
            ""
    );

    private String qualifiedName;

    private String fullName;

    private WritableScope members = null;

    private ClassSymbol classSymbol;

    private ModuleSymbol moduleSymbol;

    public PackageSymbol(final PackageSymbol parentPackage,
                         final String packageName) {
        super(ElementKind.PACKAGE, 0, packageName, new CPackageType(null), parentPackage);
        asType().setElement(this);
        if (packageName.contains(".")) {
            throw new IllegalArgumentException();
        }
        this.fullName = createFullName(parentPackage, packageName);
    }

    @Override
    protected void onEnclosingChanged() {
        fullName = null;
        qualifiedName = null;
    }

    public String getFullName() {
        if (fullName == null) {
            fullName = createFullName(
                    getEnclosingElement(),
                    getSimpleName()
            );
        }

        return fullName;
    }

    @Override
    public ModuleSymbol getModuleSymbol() {
        return moduleSymbol;
    }

    public void setModuleSymbol(final ModuleSymbol moduleSymbol) {
        this.moduleSymbol = moduleSymbol;
    }

    @Override
    public String getQualifiedName() {
        if (qualifiedName != null) {
            return qualifiedName;
        }

        final var parentPackage = (PackageElement) getEnclosingElement();

        if (parentPackage == null) {
            qualifiedName = getSimpleName();
        } else {
            final var parentName = parentPackage.getQualifiedName();
            qualifiedName = parentName + "." + getSimpleName();
        }
        return qualifiedName;
    }

    @Override
    public WritableScope getMembers() {
        complete();

        if (members == null) {
            members = new WritableScope();
        }
        return members;
    }

    public void addEnclosedElement(final Symbol enclosedElement) {
        if (members == null) {
            members = new WritableScope();
        }
        members.define(enclosedElement);
    }

    public void setMembers(final WritableScope members) {
        this.members = members;
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitPackage(this, p);
    }

    @Override
    public <R, P> R accept(final SymbolVisitor<R, P> v, final P p) {
        return v.visitPackage(this, p);
    }

    @Override
    public boolean isUnnamed() {
        return this == UNNAMED_PACKAGE;
    }

    public ClassSymbol getClassSymbol() {
        return classSymbol;
    }

    public void setClassSymbol(final ClassSymbol classSymbol) {
        this.classSymbol = classSymbol;
    }

}
