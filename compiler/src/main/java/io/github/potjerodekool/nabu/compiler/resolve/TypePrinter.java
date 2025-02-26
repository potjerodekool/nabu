package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.List;

public class TypePrinter implements TypeVisitor<Object, Object> {

    private final StringBuilder builder = new StringBuilder();

    @Override
    public Object visitUnknownType(final TypeMirror typeMirror, final Object param) {
        throw new TodoException();
    }

    @Override
    public Object visitDeclaredType(final DeclaredType classType, final Object param) {
        final var clazz = (TypeElement) classType.asElement();
        print(clazz.getQualifiedName());

        if (!classType.getTypeArguments().isEmpty()) {
            print("<");
            printList(classType.getTypeArguments());
            print(">");
        }

        return null;
    }

    @Override
    public Object visitMethodType(final ExecutableType methodType, final Object param) {
        methodType.getReturnType().accept(this, param);
        print(" ");
        final var argumentTypes = methodType.getParameterTypes();
        print("(");
        printList(argumentTypes);
        print(")");
        return null;
    }

    private void printList(final List<? extends TypeMirror> types,
                           final String sep) {
        if (types.isEmpty()) {
            return;
        }

        final var lastIndex = types.size() - 1;

        for (int i = 0; i < lastIndex; i++) {
            types.get(i).accept(this, null);
            print(sep);
        }

        types.get(lastIndex).accept(this, null);
    }

    private void printList(final List<? extends TypeMirror> types) {
        printList(types, ", ");
    }

    @Override
    public Object visitNoType(final NoType noType, final Object param) {
        print("void");
        return null;
    }

    @Override
    public Object visitPrimitiveType(final PrimitiveType primitiveType, final Object param) {
        switch (primitiveType.getKind()) {
            case BOOLEAN -> print("boolean");
            case CHAR -> print("char");
            case BYTE -> print("byte");
            case SHORT -> print("short");
            case INT -> print("int");
            case FLOAT -> print("float");
            case LONG -> print("long");
            case DOUBLE -> print("double");
        }

        return null;
    }

    @Override
    public Object visitWildcardType(final WildcardType wildcardType, final Object param) {
        if (wildcardType.getExtendsBound() != null) {
            print("extends ");
            wildcardType.getExtendsBound().accept(this, param);
        } else if (wildcardType.getSuperBound() != null) {
            print("super ");
            wildcardType.getSuperBound().accept(this, param);
        }

        return null;
    }

    @Override
    public Object visitTypeVariable(final TypeVariable typeVariable, final Object param) {
        final var name = typeVariable.asElement().getSimpleName();
        print(name);

        if (typeVariable.getUpperBound() != null) {
            print(":");
            typeVariable.getUpperBound().accept(this, param);
        }

        if (typeVariable.getLowerBound() != null) {
            print(":");
            typeVariable.getLowerBound().accept(this, param);
        }

        return null;
    }

    @Override
    public Object visitIntersectionType(final IntersectionType intersectionType, final Object param) {
        printList(intersectionType.getBounds(), " & ");
        return null;
    }

    private void print(final String text) {
        builder.append(text);
    }

    public String getText() {
        return builder.toString();
    }
}
