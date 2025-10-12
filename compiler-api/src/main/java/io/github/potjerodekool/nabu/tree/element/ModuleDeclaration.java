package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

import java.util.List;

public interface ModuleDeclaration extends Tree {

    List<AnnotationTree> getAnnotations();

    ModuleKind getKind();

    ExpressionTree getIdentifier();

    List<? extends DirectiveTree> getDirectives();

    ModuleElement getModuleSymbol();

    enum ModuleKind {
        OPEN,
        STRONG
    }
}
