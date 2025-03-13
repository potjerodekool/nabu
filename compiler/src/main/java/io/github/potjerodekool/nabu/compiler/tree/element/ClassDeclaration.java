package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.tree.CModifiers;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TypeParameterTree;
import io.github.potjerodekool.nabu.compiler.tree.element.impl.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;

import java.util.List;

public interface ClassDeclaration extends Statement {

    String getSimpleName();

    CModifiers getModifiers();

    ExpressionTree getExtendion();

    Kind getKind();

    List<Tree> getEnclosedElements();

    List<ExpressionTree> getImplementing();

    ExpressionTree getExtends();

    List<TypeParameterTree> getTypeParameters();

    TypeElement getClassSymbol();

    void setClassSymbol(TypeElement typeElement);

    CClassDeclaration enclosedElement(Tree tree);

    CClassDeclaration enclosedElement(Tree tree, int index);

    CClassDeclaration enclosedElements(List<Tree> newEnclosingElements);
}
