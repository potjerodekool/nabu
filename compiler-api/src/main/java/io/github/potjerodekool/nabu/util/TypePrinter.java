package io.github.potjerodekool.nabu.util;

import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.type.*;

import java.util.List;

/**
 * Utility to print types.
 */
public class TypePrinter {
    private TypePrinter() {
    }

    public static String print(final TypeMirror typeMirror) {
        if (typeMirror == null) {
            return "UNKNOWN";
        }

        final StringBuilder builder = new StringBuilder();
        final var printer = new StandardTypePrinter(builder);

        typeMirror.accept(printer, null);
        return printer.getText();
    }
}

abstract class AbstractTypePrinter implements TypeVisitor<Object, Object> {

    private final StringBuilder builder;

    AbstractTypePrinter(final StringBuilder builder) {
        this.builder = builder;
    }

    StringBuilder getBuilder() {
        return builder;
    }

    @Override
    public final Object visitUnknownType(final TypeMirror typeMirror,
                                         final Object param) {
        return null;
    }

    @Override
    public final Object visitDeclaredType(final DeclaredType declaredType,
                                          final Object param) {
        final var clazz = (TypeElement) declaredType.asElement();
        print(clazz.getQualifiedName());

        if (!declaredType.getTypeArguments().isEmpty()) {
            print("<");
            printList(declaredType.getTypeArguments(), param);
            print(">");
        }

        return null;
    }

    @Override
    public final Object visitMethodType(final ExecutableType methodType,
                                        final Object param) {
        final var methodName = methodType.getMethodSymbol().getSimpleName();
        final var typeVariables = methodType.getTypeVariables();

        if (!typeVariables.isEmpty()) {
            print("<");
            printList(typeVariables, param);
            print("> ");
        }
        methodType.getReturnType().accept(this, param);
        print(" ");
        final var argumentTypes = methodType.getParameterTypes();
        print(methodName);
        print("(");
        printList(argumentTypes, param);
        print(")");

        final var thrownTypes = methodType.getThrownTypes();

        if (!thrownTypes.isEmpty()) {
            print(" throws ");
            printList(thrownTypes, ",", param);
        }

        return null;
    }

    private void printList(final List<? extends TypeMirror> types,
                           final String sep,
                           final Object param) {
        if (types.isEmpty()) {
            return;
        }

        final var lastIndex = types.size() - 1;

        for (int i = 0; i < lastIndex; i++) {
            types.get(i).accept(this, param);
            print(sep);
        }

        types.get(lastIndex).accept(this, param);
    }

    private void printList(final List<? extends TypeMirror> types,
                           final Object param) {
        printList(types, ", ", param);
    }

    @Override
    public final Object visitNoType(final NoType noType,
                                    final Object param) {
        print("void");
        return null;
    }

    @Override
    public final Object visitPrimitiveType(final PrimitiveType primitiveType,
                                           final Object param) {
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
    public final Object visitWildcardType(final WildcardType wildcardType,
                                          final Object param) {
        if (wildcardType.getExtendsBound() != null) {
            print("? extends ");
            wildcardType.getExtendsBound().accept(this, param);
        } else if (wildcardType.getSuperBound() != null) {
            print("? super ");
            wildcardType.getSuperBound().accept(this, param);
        } else {
            print(Constants.OBJECT);
        }

        return null;
    }

    @Override
    public final Object visitIntersectionType(final IntersectionType intersectionType,
                                              final Object param) {
        printList(intersectionType.getBounds(), " & ", param);
        return null;
    }

    void print(final String text) {
        builder.append(text);
    }

    public String getText() {
        return builder.toString();
    }

}

class StandardTypePrinter extends AbstractTypePrinter {

    StandardTypePrinter(final StringBuilder builder) {
        super(builder);
    }

    @Override
    public Object visitTypeVariable(final TypeVariable typeVariable,
                                    final Object param) {
        final var name = typeVariable.asElement().getSimpleName();
        print(name);

        final var typeVariablePrinter = new TypeVariablePrinter(getBuilder());

        if (typeVariable.getUpperBound() != null) {
            print(":");
            typeVariable.getUpperBound().accept(typeVariablePrinter, param);
        }

        if (typeVariable.getLowerBound() != null) {
            print(":");
            typeVariable.getLowerBound().accept(typeVariablePrinter, param);
        }

        return null;
    }

}

class TypeVariablePrinter extends AbstractTypePrinter {

    TypeVariablePrinter(final StringBuilder builder) {
        super(builder);
    }

    @Override
    public Object visitTypeVariable(final TypeVariable typeVariable,
                                    final Object param) {
        final var name = typeVariable.asElement().getSimpleName();
        print(name);
        return null;
    }

}