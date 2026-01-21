package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Implementation of AnnotationTree.
 */
public class CAnnotationTree extends CExpressionTree implements AnnotationTree {

    private final IdentifierTree name;
    private final List<ExpressionTree> arguments = new ArrayList<>();

    public CAnnotationTree(final IdentifierTree name,
                           final List<ExpressionTree> arguments,
                           final int lineNumber,
                           final int columnNumber) {
        super(lineNumber, columnNumber);
        this.name = name;
        this.arguments.addAll(arguments);
    }


    public IdentifierTree getName() {
        return name;
    }

    public List<ExpressionTree> getArguments() {
        return arguments;
    }

    @Override
    public ExpressionTree getArgumentValue(final String name) {
        return arguments.stream()
                .flatMap(CollectionUtils.mapOnly(AssignmentExpressionTree.class))
                .filter(assignmentExpressionTree -> {
                    final var identifier = (IdentifierTree) assignmentExpressionTree.getLeft();
                    return identifier.getName().equals(name);
                })
                .map(AssignmentExpressionTree::getRight)
                .findFirst()
                .orElseGet(() -> {
                    final var annotationElement = getName().getType().asTypeElement();
                    final var value = ElementFilter.methodsIn(annotationElement.getEnclosedElements()).stream()
                            .filter(method -> method.getSimpleName().equals(name))
                            .map(ExecutableElement::getDefaultValue)
                            .filter(Objects::nonNull)
                            .map(AnnotationValue::getValue)
                            .findFirst()
                            .orElse(null);

                    return extractArgumentValue((VariableElement) value);
                });
    }

    private ExpressionTree extractArgumentValue(final Object value) {
        if (value == null) {
            return null;
        } else if (value instanceof VariableElement variableElement && variableElement.getKind() == ElementKind.ENUM_CONSTANT) {
            final var enumName = variableElement.asType().asTypeElement().getQualifiedName();
            return TreeMaker.fieldAccessExpressionTree(
                    IdentifierTree.create(enumName),
                    IdentifierTree.create(variableElement.getSimpleName()),
                    0,
                    0
            );
        } else {
            return null;
        }
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitAnnotation(this, param);
    }
}
