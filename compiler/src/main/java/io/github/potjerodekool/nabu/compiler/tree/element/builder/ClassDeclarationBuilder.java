package io.github.potjerodekool.nabu.compiler.tree.element.builder;

import io.github.potjerodekool.nabu.compiler.tree.Modifiers;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TypeParameterTree;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.element.impl.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.StatementTreeBuilder;

import java.util.ArrayList;
import java.util.List;

public class ClassDeclarationBuilder extends StatementTreeBuilder<CClassDeclaration, ClassDeclarationBuilder> {

    private Modifiers modifiers;
    private Kind kind;
    private String simpleName;
    private final List<Tree> enclosedElements = new ArrayList<>();
    private ExpressionTree extending;
    private final List<TypeParameterTree> typeParameters = new ArrayList<>();
    private final List<ExpressionTree> implementing = new ArrayList<>();
    private final List<IdentifierTree> permits;

    public ClassDeclarationBuilder() {
        super();
        this.permits = new ArrayList<>();
    }

    public ClassDeclarationBuilder(final CClassDeclaration classDeclaration) {
        super(classDeclaration);
        this.modifiers = classDeclaration.getModifiers();
        this.kind = classDeclaration.getKind();
        this.simpleName = classDeclaration.getSimpleName();
        this.enclosedElements.addAll(classDeclaration.getEnclosedElements());
        this.extending = classDeclaration.getExtending();
        this.typeParameters.addAll(classDeclaration.getTypeParameters());
        this.implementing.addAll(classDeclaration.getImplementing());
        this.permits = List.copyOf(classDeclaration.getPermits());
    }

    public Kind getKind() {
        return kind;
    }

    public ClassDeclarationBuilder kind(final Kind kind) {
        this.kind = kind;
        return this;
    }

    public String getSimpleName() {
        return simpleName;
    }

    public ExpressionTree getExtending() {
        return extending;
    }

    public ClassDeclarationBuilder extending(final ExpressionTree extending) {
        this.extending = extending;
        return this;
    }

    public List<Tree> getEnclosedElements() {
        return enclosedElements;
    }

    public ClassDeclarationBuilder enclosedElements(final List<Tree> enclosedElements) {
        this.enclosedElements.clear();
        this.enclosedElements.addAll(enclosedElements);
        return this;
    }

    public List<TypeParameterTree> getTypeParameters() {
        return typeParameters;
    }

    public List<ExpressionTree> getImplementing() {
        return implementing;
    }

    public ClassDeclarationBuilder implemention(final List<ExpressionTree> implementing) {
        this.implementing.clear();
        this.implementing.addAll(implementing);
        return this;
    }

    public List<IdentifierTree> getPermits() {
        return permits;
    }

    public ClassDeclarationBuilder permits(final List<IdentifierTree> permits) {
        this.permits.clear();
        this.permits.addAll(permits);
        return this;
    }

    @Override
    public ClassDeclarationBuilder self() {
        return this;
    }

    @Override
    public CClassDeclaration build() {
        return new CClassDeclaration(this);
    }

    public ClassDeclarationBuilder typeParameters(final List<TypeParameterTree> typeParameters) {
        this.typeParameters.addAll(typeParameters);
        return this;
    }

    public Modifiers getModifiers() {
        return modifiers;
    }

    public ClassDeclarationBuilder modifiers(final Modifiers modifiers) {
        this.modifiers = modifiers;
        return this;
    }

    public ClassDeclarationBuilder simpleName(final String name) {
        this.simpleName = name;
        return this;
    }
}
