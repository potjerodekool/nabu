package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.compiler.util.impl.TypesImpl;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;

public class SuperTypeVisitor extends UnaryVisitor<AbstractType> {

    private final TypesImpl types;

    public SuperTypeVisitor(final TypesImpl types) {
        this.types = types;
    }

    @Override
    public AbstractType visitUnknownType(final TypeMirror typeMirror, final Void subType) {
        return (AbstractType) typeMirror;
    }

    @Override
    public AbstractType visitDeclaredType(final DeclaredType declaredType,
                                          final Void subType) {
        final var classType = (CClassType) declaredType;

        if (classType.getSupertypeField() == null) {
            var supertype = (AbstractType) classType.asTypeElement().getSuperclass();

            if (classType.isInterface()) {
                supertype = (AbstractType) ((CClassType) classType.asTypeElement().asType()).getSupertypeField();

                if (supertype == null) {
                    //TODO Fix somewhere else that super type fields is set.
                    //After that this code can be deleted.
                    final var objectType = types.getObjectType();
                    supertype = (AbstractType) objectType;
                    classType.setSupertypeField((CClassType) objectType);
                }
            }

            if (supertype == null) {
                supertype = (AbstractType) classType.asTypeElement().getSuperclass();
                classType.setSupertypeField((CClassType) supertype);
            }

            if (classType.getSupertypeField() == null) {
                final var actuals = types.classBound(classType).getAllParameters();
                final var formals = classType.asTypeElement().asType().getAllParameters();
                if (classType.hasErasedSupertypes()) {
                    classType.setSupertypeField((CClassType) types.erasureRecursive(supertype));
                } else if (!formals.isEmpty()) {
                    classType.setSupertypeField((CClassType) types.subst(supertype, formals, actuals));
                }
                else {
                    classType.setSupertypeField((CClassType) supertype);
                }
            }
        }
        return (AbstractType) classType.getSupertypeField();
    }
}
