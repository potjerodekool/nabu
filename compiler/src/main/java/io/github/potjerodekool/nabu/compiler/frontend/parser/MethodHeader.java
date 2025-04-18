package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TypeParameterTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

import java.util.List;

public record MethodHeader(List<TypeParameterTree> typeParameters,
                           List<AnnotationTree> annotations,
                           MethodDeclarator functionDeclarator,
                           ExpressionTree result, List<Tree> exceptions) {
}