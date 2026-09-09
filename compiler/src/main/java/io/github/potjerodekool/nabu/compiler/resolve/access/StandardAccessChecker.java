package io.github.potjerodekool.nabu.compiler.resolve.access;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;

public final class StandardAccessChecker implements AccessChecker {

    public static final StandardAccessChecker INSTANCE = new StandardAccessChecker();

    private StandardAccessChecker() {
    }

    @Override
    public boolean isAccessible(final Element element,
                                final TypeElement classSymbol) {
        if (element instanceof VariableElement variableElement) {
            return isAccessible(variableElement, classSymbol);
        } else {
            return true;
        }
    }

private boolean isAccessible(final VariableElement variableElement,
                                  final TypeElement classSymbol) {
        if (variableElement.getKind() != ElementKind.FIELD) {
            return true;
        }

        final var declaringClass = (TypeElement) variableElement.getEnclosingElement();

        if (variableElement.isPrivate()) {
            return classSymbol.getQualifiedName().equals(declaringClass.getQualifiedName())
                    || sameTopLevelClass(declaringClass, classSymbol);
        } else {
            return true;
        }
    }

    private boolean sameTopLevelClass(final TypeElement classA,
                                      final TypeElement classB) {
        return topLevelOf(classA).equals(topLevelOf(classB));
    }

    private Object topLevelOf(final TypeElement classSymbol) {
        var current = classSymbol;

        while (current.getEnclosingElement() instanceof TypeElement enclosing) {
            current = enclosing;
        }

        return current.getQualifiedName();
    }
}
