package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.ast.element.QualifiedNameable;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;

import java.util.List;

public interface PackageDeclaration extends Tree, QualifiedNameable {


    String getPackageName();

    List<AnnotationTree> getAnnotations();

}
