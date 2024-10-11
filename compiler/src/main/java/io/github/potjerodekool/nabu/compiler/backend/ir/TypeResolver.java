package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.CPrimitiveType;
import io.github.potjerodekool.nabu.compiler.tree.expression.CTypeApply;
import io.github.potjerodekool.nabu.compiler.type.*;
import io.github.potjerodekool.nabu.compiler.type.VariableType;

import java.util.List;

class TypeResolver extends AbstractTreeVisitor<IType, Object> implements TypeVisitor<IType, Object> {

    @Override
    public IType visitTypeIdentifier(final CTypeApply typeIdentifier, final Object param) {
        final var typeParameters = typeIdentifier.getTypeParameters();
        final List<IType> types;

        if (typeParameters != null) {
            types = typeParameters.stream()
                    .map(typeParameter -> typeParameter.accept(this, param))
                    .toList();
        } else {
            types = null;
        }

        final var type = (ClassType) typeIdentifier.getType();
        final var clazz = (ClassSymbol) type.asElement();

        return IReferenceType.create(
                clazz.getQualifiedName(),
                types
        );
    }

    @Override
    public IType visitIdentifier(final CIdent ident, final Object param) {
        final var type = ident.getType();
        return type.accept(this, param);
    }

    @Override
    public IType visitPrimitiveType(final CPrimitiveType primitiveType, final Object param) {
        return switch (primitiveType.getKind()) {
            case BOOLEAN -> IPrimitiveType.BOOLEAN;
            case INT -> IPrimitiveType.INT;
            case BYTE -> IPrimitiveType.BYTE;
            case SHORT -> IPrimitiveType.SHORT;
            case LONG -> IPrimitiveType.LONG;
            case CHAR -> IPrimitiveType.CHAR;
            case FLOAT -> IPrimitiveType.FLOAT;
            case DOUBLE -> IPrimitiveType.DOUBLE;
        };
    }

    @Override
    public IType visitTypeNameExpression(final CTypeNameExpression typeNameExpression, final Object param) {
        final var className = asString(typeNameExpression);
        return IReferenceType.create(className);
    }

    private String asString(final CExpression expression) {
        return switch (expression) {
            case CTypeNameExpression typeNameExpression -> {
                final var packageName = asString(typeNameExpression.getPackageName());
                final var className = asString(typeNameExpression.getIdenifier());
                yield packageName + "." + className;
            }
            case CIdent ident -> ident.getName();
            case CTypeApply typeIdentifier -> typeIdentifier.getName();
            default -> "";
        };
    }

    @Override
    public IType visitArrayType(final ArrayType arrayType, final Object param) {
        throw new TodoException();
    }

    @Override
    public IType visitClassType(final ClassType classType, final Object param) {
        final var clazz = (ClassSymbol) classType.asElement();

        if (classType.getParameterTypes() != null) {
            throw new TodoException();
        }


        return IReferenceType.create(
                clazz.getQualifiedName(),
                null
        );
    }

    @Override
    public IType visitMethodType(final MethodType methodType, final Object param) {
        throw new TodoException();
    }

    @Override
    public IType visitVoidType(final VoidType voidType, final Object param) {
        throw new TodoException();
    }

    @Override
    public IType visitPrimitiveType(final PrimitiveType primitiveType, final Object param) {
        throw new TodoException();
    }

    @Override
    public IType visitNullType(final NullType nullType, final Object param) {
        throw new TodoException();
    }

    @Override
    public IType visitVariableType(final VariableType variableType, final Object param) {
        return null;
    }

    @Override
    public IType visitWildcardType(final WildcardType wildcardType, final Object param) {
        throw new TodoException();
    }

    @Override
    public IType visitTypeVariable(final TypeVariable typeVariable, final Object param) {
        throw new TodoException();
    }
}
