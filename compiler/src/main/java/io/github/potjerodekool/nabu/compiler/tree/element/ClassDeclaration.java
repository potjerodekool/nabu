package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.tree.CModifiers;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TypeParameterTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementTree;

import java.util.List;

public interface ClassDeclaration extends StatementTree {

    String getSimpleName();

    CModifiers getModifiers();

    Kind getKind();

    List<Tree> getEnclosedElements();

    List<ExpressionTree> getImplementing();

    ExpressionTree getExtending();

    List<TypeParameterTree> getTypeParameters();

    List<IdentifierTree> getPermits();

    TypeElement getClassSymbol();

    void setClassSymbol(TypeElement typeElement);

    ClassDeclaration enclosedElement(Tree tree);

    ClassDeclaration enclosedElement(Tree tree, int index);

    ClassDeclaration enclosedElements(List<Tree> newEnclosingElements);
}
