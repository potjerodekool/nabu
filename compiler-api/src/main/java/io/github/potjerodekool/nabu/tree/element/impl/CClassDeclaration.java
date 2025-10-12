package io.github.potjerodekool.nabu.tree.element.impl;

import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.TypeParameterTree;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.NestingKind;
import io.github.potjerodekool.nabu.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CStatementTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CClassDeclaration extends CStatementTree implements ClassDeclaration {

    private final Kind kind;

    private final NestingKind nestingKind;

    private final String simpleName;

    private final List<Tree> enclosedElements = new ArrayList<>();

    private final Modifiers modifiers;

    private TypeElement classSymbol;

    private final List<TypeParameterTree> typeParameters = new ArrayList<>();

    private final List<ExpressionTree> implementing = new ArrayList<>();

    private final ExpressionTree extending;

    private final List<IdentifierTree> permits;

    public CClassDeclaration(final Kind kind,
                             final NestingKind nestingKind,
                             final Modifiers modifiers,
                             final String simpleName,
                             final List<Tree> enclosedElements,
                             final List<TypeParameterTree> typeParameters,
                             final List<ExpressionTree> implementing,
                             final ExpressionTree extending,
                             final List<IdentifierTree> permits,
                             final int lineNumber,
                             final int columnNumber) {
        super(lineNumber, columnNumber);
        this.kind = kind;
        this.nestingKind = nestingKind;
        this.modifiers = modifiers;
        this.simpleName = simpleName;

        enclosedElements.forEach(Objects::requireNonNull);

        this.enclosedElements.addAll(enclosedElements);
        this.typeParameters.addAll(typeParameters);
        this.implementing.addAll(implementing);
        this.extending = extending;
        this.permits = permits;
    }

    public CClassDeclaration(final ClassDeclarationBuilder classDeclarationBuilder) {
        super(classDeclarationBuilder);
        this.kind = classDeclarationBuilder.getKind();
        this.nestingKind = classDeclarationBuilder.getNestingKind();
        this.simpleName = classDeclarationBuilder.getSimpleName();
        this.enclosedElements.addAll(classDeclarationBuilder.getEnclosedElements());

        enclosedElements.forEach(Objects::requireNonNull);
        this.modifiers = classDeclarationBuilder.getModifiers();
        this.typeParameters.addAll(classDeclarationBuilder.getTypeParameters());
        this.implementing.addAll(classDeclarationBuilder.getImplementing());
        this.extending = classDeclarationBuilder.getExtending();
        this.permits = classDeclarationBuilder.getPermits();
    }

    @Override
    public String getSimpleName() {
        return simpleName;
    }

    @Override
    public Modifiers getModifiers() {
        return modifiers;
    }

    @Override
    public ExpressionTree getExtending() {
        return extending;
    }

    @Override
    public Kind getKind() {
        return kind;
    }

    @Override
    public NestingKind getNestingKind() {
        return nestingKind;
    }

    @Override
    public List<Tree> getEnclosedElements() {
        return enclosedElements;
    }


    @Override
    public CClassDeclaration enclosedElement(final Tree tree) {
        Objects.requireNonNull(tree);
        this.enclosedElements.add(tree);
        return this;
    }

    @Override
    public CClassDeclaration enclosedElement(final Tree constructor, final int index) {
        Objects.requireNonNull(constructor);
        this.enclosedElements.add(index, constructor);
        return this;
    }

    @Override
    public CClassDeclaration enclosedElements(final List<Tree> enclosedElements) {
        this.enclosedElements.clear();
        this.enclosedElements.addAll(enclosedElements);
        enclosedElements.forEach(Objects::requireNonNull);
        return this;
    }

    public ClassDeclaration removeEnclosedElements(final List<? extends Tree> enclosedElements) {
        this.enclosedElements.removeAll(enclosedElements);
        return this;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitClass(this, param);
    }

    @Override
    public List<ExpressionTree> getImplementing() {
        return implementing;
    }

    @Override
    public List<TypeParameterTree> getTypeParameters() {
        return typeParameters;
    }

    @Override
    public List<IdentifierTree> getPermits() {
        return permits;
    }

    public ClassDeclarationBuilder builder() {
        return new ClassDeclarationBuilder(this);
    }

    @Override
    public TypeElement getClassSymbol() {
        return classSymbol;
    }

    public void setClassSymbol(final TypeElement classSymbol) {
        this.classSymbol = classSymbol;
    }

}
