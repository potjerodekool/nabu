package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TypeParameterTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;

import java.util.List;

public interface ClassDeclaration extends StatementTree {

    String getSimpleName();

    Modifiers getModifiers();

    Kind getKind();

    NestingKind getNestingKind();

    List<Tree> getEnclosedElements();

    List<ExpressionTree> getImplementing();

    ExpressionTree getExtending();

    List<TypeParameterTree> getTypeParameters();

    List<IdentifierTree> getPermits();

    TypeElement getClassSymbol();

    ClassDeclaration enclosedElement(Tree tree);

    ClassDeclaration enclosedElement(Tree tree, int index);

    ClassDeclaration enclosedElements(List<Tree> newEnclosingElements);
}
