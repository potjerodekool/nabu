package io.github.potjerodekool.nabu.compiler.util;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.QualifiedNameable;

public final class ElementUtils {

    private ElementUtils() {
    }

    public static String getQualifiedName(final Element element) {
        if (element instanceof QualifiedNameable qualifiedNameable) {
            return qualifiedNameable.getQualifiedName();
        } else {
            return element.getSimpleName();
        }
    }
}
