package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.tree.expression.AssignmentExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.util.CollectionUtils;

import java.util.List;
import java.util.stream.Collectors;

public class AnnotatePhase extends AbstractTreeVisitor<Object, CompilerContext> {

    public static FileObjectAndCompilationUnit annotate(final FileObjectAndCompilationUnit fileObjectAndCompilationUnit,
                                                        final CompilerContext compilerContext) {
        final var phase = new AnnotatePhase();
        fileObjectAndCompilationUnit.compilationUnit().accept(phase, compilerContext);
        return fileObjectAndCompilationUnit;
    }

    @Override
    public Object visitAnnotation(final AnnotationTree annotationTree, final CompilerContext compilerContext) {
        final var methods = ElementFilter.methodsIn(annotationTree.getName().getType().asTypeElement().getEnclosedElements()).stream()
                .collect(
                        Collectors.toMap(
                                Element::getSimpleName,
                                ExecutableElement::getReturnType
                        )
                );

        annotationTree.getArguments().stream()
                .flatMap(CollectionUtils.mapOnly(AssignmentExpressionTree.class))
                .map(argument -> {
                    final var name = ((IdentifierTree) argument.getLeft()).getName();
                    final var returnType = methods.get(name);

                    if (returnType != null
                            && returnType.isArrayType()
                            && argument.getRight() instanceof LiteralExpressionTree literal) {
                        if (!literal.getType().isArrayType()) {
                            final var arrayType = compilerContext.getClassElementLoader().getTypes().getArrayType(literal.getType());
                            return TreeMaker.newArrayExpression(
                                    null,
                                    List.of(),
                                    List.of(argument),
                                    -1,
                                    -1
                            );
                        }
                    }

                    return argument;
                });

        return super.visitAnnotation(annotationTree, compilerContext);
    }
}
