package io.github.potjerodekool.nabu.compiler.tree.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.tree.Modifiers;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.TypeParameterTree;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CStatementTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CClassDeclaration extends CStatementTree implements ClassDeclaration {

    private final Kind kind;

    private final String simpleName;

    private final List<Tree> enclosedElements = new ArrayList<>();

    private final Modifiers modifiers;

    private ClassSymbol classSymbol;

    private final List<TypeParameterTree> typeParameters = new ArrayList<>();

    private final List<ExpressionTree> implementing = new ArrayList<>();

    private final ExpressionTree extending;

    private final List<IdentifierTree> permits;

    public CClassDeclaration(final Kind kind,
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
        this.simpleName = classDeclarationBuilder.getSimpleName();
        this.enclosedElements.addAll(classDeclarationBuilder.getEnclosedElements());

        enclosedElements.forEach(Objects::requireNonNull);
        this.modifiers = classDeclarationBuilder.getModifiers();
        this.typeParameters.addAll(classDeclarationBuilder.getTypeParameters());
        this.implementing.addAll(classDeclarationBuilder.getImplementing());
        this.extending = classDeclarationBuilder.getExtending();
        this.permits = classDeclarationBuilder.getPermits();
    }

    public String getSimpleName() {
        return simpleName;
    }

    public Modifiers getModifiers() {
        return modifiers;
    }

    @Override
    public ExpressionTree getExtending() {
        return extending;
    }

    public Kind getKind() {
        return kind;
    }

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

    public List<ExpressionTree> getImplementing() {
        return implementing;
    }

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
    public ClassSymbol getClassSymbol() {
        return classSymbol;
    }

    public void setClassSymbol(final ClassSymbol classSymbol) {
        this.classSymbol = classSymbol;
    }

}
