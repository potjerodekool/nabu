package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TypeParameterTree;
import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;

import java.util.List;

public record MethodHeader(List<TypeParameterTree> typeParameters,
                           List<AnnotationTree> annotations,
                           MethodDeclarator functionDeclarator,
                           Tree result,
                           List<Tree> exceptions) {
}