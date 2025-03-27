package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.ast.element.ModuleElement;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

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
