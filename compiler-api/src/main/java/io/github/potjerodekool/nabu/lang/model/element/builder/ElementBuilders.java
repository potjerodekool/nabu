package io.github.potjerodekool.nabu.lang.model.element.builder;

import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.PackageElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;

/**
 * Utilities for building elements.
 * Normally only VariableElementBuilder should be used
 * to create local variables.
 * For example when rewriting the AST.
 * Other elements are normally only created in tests.
 */
public interface ElementBuilders {

    /**
     * @return Returns a builder for variables.
     */
    VariableElementBuilder<? extends VariableElement> variableElementBuilder();

    /**
     * @return Returns a builder for executable elements.
     */
    ExecutableElementBuilder<? extends ExecutableElement> executableElementBuilder();

    /**
     * @return Return a builder for type elements.
     */
    TypeElementBuilder<? extends TypeElement> typeElementBuilder();

    /**
     * @return Returns a builder for package elements.
     */
    PackageElementBuilder<? extends PackageElement> packageElementBuilder();

    /**
     * @param name A class name.
     * @return Return an error element for the given class name.
     */
    TypeElement createErrorSymbol(String name);
}
