package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TypeParameterTree;
import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

import java.util.List;

public record MethodHeader(List<TypeParameterTree> typeParameters,
                           List<AnnotationTree> annotations,
                           MethodDeclarator functionDeclarator,
                           ExpressionTree result, List<Tree> exceptions) {
}