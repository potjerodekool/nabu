package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.element.impl.CModuleDeclaration;
import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

import java.util.List;

/**
 * A module declaration.
 */
public interface ModuleDeclaration extends Tree {

    List<AnnotationTree> getAnnotations();

    ModuleKind getKind();

    ExpressionTree getIdentifier();

    List<? extends DirectiveTree> getDirectives();

    ModuleElement getModuleSymbol();

    static ModuleDeclaration create(final int lineNumber,
                                    final int columnNumber,
                                    final ModuleKind kind,
                                    final ExpressionTree identifier,
                                    final List<DirectiveTree> directives,
                                    final List<AnnotationTree> annotations) {
        return new CModuleDeclaration(lineNumber, columnNumber, kind, identifier, directives, annotations);
    }

    enum ModuleKind {
        OPEN,
        STRONG
    }
}
