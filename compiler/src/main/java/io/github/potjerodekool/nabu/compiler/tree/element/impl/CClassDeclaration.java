package io.github.potjerodekool.nabu.compiler.tree.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.tree.CModifiers;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.TypeParameterTree;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CStatement;

import java.util.ArrayList;
import java.util.List;

public class CClassDeclaration extends CStatement implements ClassDeclaration {

    private final Kind kind;

    private final String simpleName;

    private final List<Tree> enclosedElements = new ArrayList<>();

    private final CModifiers modifiers;

    private TypeElement typeElement;

    private final List<TypeParameterTree> typeParameters = new ArrayList<>();

    private final List<ExpressionTree> implementing = new ArrayList<>();

    private final ExpressionTree extendion;

    public CClassDeclaration(final Kind kind,
                             final CModifiers modifiers,
                             final String simpleName,
                             final List<Tree> enclosedElements,
                             final List<TypeParameterTree> typeParameters,
                             final List<ExpressionTree> implementing,
                             final ExpressionTree extendion,
                             final int lineNumber,
                             final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.kind = kind;
        this.modifiers = modifiers;
        this.simpleName = simpleName;
        this.enclosedElements.addAll(enclosedElements);
        this.typeParameters.addAll(typeParameters);
        this.implementing.addAll(implementing);
        this.extendion = extendion;
    }

    public CClassDeclaration(final ClassDeclarationBuilder classDeclarationBuilder) {
        super(classDeclarationBuilder);
        this.kind = classDeclarationBuilder.kind;
        this.simpleName = classDeclarationBuilder.simpleName;
        this.enclosedElements.addAll(classDeclarationBuilder.enclosedElements);
        this.modifiers = classDeclarationBuilder.modifiers;
        this.typeParameters.addAll(classDeclarationBuilder.typeParameters);
        this.implementing.addAll(classDeclarationBuilder.implementing);
        this.extendion = classDeclarationBuilder.extendion;
    }

    public String getSimpleName() {
        return simpleName;
    }

    public CModifiers getModifiers() {
        return modifiers;
    }

    public ExpressionTree getExtendion() {
        return extendion;
    }

    public Kind getKind() {
        return kind;
    }

    public List<Tree> getEnclosedElements() {
        return enclosedElements;
    }


    @Override
    public CClassDeclaration enclosedElement(final Tree tree) {
        this.enclosedElements.add(tree);
        return this;
    }

    @Override
    public CClassDeclaration enclosedElement(final Tree constructor, final int index) {
        this.enclosedElements.add(index, constructor);
        return this;
    }

    public CClassDeclaration enclosedElements(final List<Tree> enclosedElements) {
        this.enclosedElements.clear();
        this.enclosedElements.addAll(enclosedElements);
        return this;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitClass(this, param);
    }

    public List<ExpressionTree> getImplementing() {
        return implementing;
    }

    public ExpressionTree getExtends() {
        return extendion;
    }

    public List<TypeParameterTree> getTypeParameters() {
        return typeParameters;
    }

    public ClassDeclarationBuilder builder() {
        return new ClassDeclarationBuilder(this);
    }

    @Override
    public TypeElement getClassSymbol() {
        return typeElement;
    }

    @Override
    public void setClassSymbol(final TypeElement typeElement) {
        this.typeElement = typeElement;
    }

    public static class ClassDeclarationBuilder extends StatementBuilder<CClassDeclaration, ClassDeclarationBuilder> {

        private CModifiers modifiers;
        private Kind kind;
        private String simpleName;
        private final List<Tree> enclosedElements = new ArrayList<>();
        public ExpressionTree extendion;
        private final List<TypeParameterTree> typeParameters = new ArrayList<>();
        private final List<ExpressionTree> implementing = new ArrayList<>();

        public ClassDeclarationBuilder() {
            super();
        }

        public ClassDeclarationBuilder(final CClassDeclaration classDeclaration) {
            super(classDeclaration);
            this.modifiers = classDeclaration.modifiers;
            this.kind = classDeclaration.kind;
            this.simpleName = classDeclaration.simpleName;
            this.enclosedElements.addAll(classDeclaration.enclosedElements);
            this.extendion = classDeclaration.extendion;
            this.typeParameters.addAll(classDeclaration.typeParameters);
            this.implementing.addAll(classDeclaration.implementing);
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

        public ClassDeclarationBuilder implementing(final List<ExpressionTree> implementing) {
            this.implementing.addAll(implementing);
            return this;
        }

        public ClassDeclarationBuilder extendion(final ExpressionTree extendion) {
            this.extendion = extendion;
            return this;
        }

        public ClassDeclarationBuilder modifiers(final CModifiers modifiers) {
            this.modifiers = modifiers;
            return this;
        }

        public ClassDeclarationBuilder simpleName(final String name) {
            this.simpleName = name;
            return this;
        }

        public ClassDeclarationBuilder enclosedElements(final List<Tree> enclosedElements) {
            this.enclosedElements.clear();
            this.enclosedElements.addAll(enclosedElements);
            return this;
        }
    }
}
