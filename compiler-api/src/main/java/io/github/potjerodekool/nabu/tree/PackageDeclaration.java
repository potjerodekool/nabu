package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.lang.model.element.PackageElement;
import io.github.potjerodekool.nabu.lang.model.element.QualifiedNameable;
import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

import java.util.List;

public interface PackageDeclaration extends Tree, QualifiedNameable {

    PackageElement getPackageElement();

    void setPackageElement(PackageElement packageElement);

    ExpressionTree getIdentifier();

    List<AnnotationTree> getAnnotations();

}
