package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.ast.element.QualifiedNameable;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

import java.util.List;

public interface PackageDeclaration extends Tree, QualifiedNameable {

    PackageElement getPackageElement();

    void setPackageElement(PackageElement packageElement);

    ExpressionTree getIdentifier();

    List<AnnotationTree> getAnnotations();

}
