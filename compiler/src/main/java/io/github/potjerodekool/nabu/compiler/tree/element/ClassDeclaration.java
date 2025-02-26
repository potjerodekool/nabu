package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.QualifiedNameable;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.TypeParameterTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

import java.util.ArrayList;
import java.util.List;

public class ClassDeclaration extends Element<ClassDeclaration> implements QualifiedNameable {

    private String qualifiedName;

    public TypeElement classSymbol;

    private final List<TypeParameterTree> typeParameters = new ArrayList<>();

    private final List<ExpressionTree> implementing = new ArrayList<>();

    private final ExpressionTree extendion;

    public ClassDeclaration(final ClassDeclarationBuilder classDeclarationBuilder) {
        super(classDeclarationBuilder);
        this.typeParameters.addAll(classDeclarationBuilder.typeParameters);
        this.implementing.addAll(classDeclarationBuilder.implementing);
        this.extendion = classDeclarationBuilder.extendion;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R,P> visitor, final P param) {
        return visitor.visitClass(this, param);
    }

    @Override
    public ClassDeclaration enclosingElement(final Element<?> enclosingElement) {
        this.qualifiedName = null;
        return super.enclosingElement(enclosingElement);
    }

    @Override
    public String getQualifiedName() {
        if (qualifiedName == null) {
            final var enclossing = getEnclosingElement();

            if (enclossing == null) {
                qualifiedName = getSimpleName();
            } else if (enclossing instanceof QualifiedNameable qualifiedNameable) {
                final var enclosingName = qualifiedNameable.getQualifiedName();
                final var simpleName = getSimpleName();
                qualifiedName = enclosingName + "." + simpleName;
            }
        }

        return qualifiedName;
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

    public static class ClassDeclarationBuilder extends CElementBuilder<ClassDeclaration, ClassDeclarationBuilder> {

        public ExpressionTree extendion;
        private final List<TypeParameterTree> typeParameters = new ArrayList<>();
        private final List<ExpressionTree> implementing = new ArrayList<>();

        public ClassDeclarationBuilder() {
            super();
        }

        public ClassDeclarationBuilder(final ClassDeclaration classDeclaration) {
            super(classDeclaration);
        }

        @Override
        public ClassDeclarationBuilder self() {
            return this;
        }

        @Override
        public ClassDeclaration build() {
            return new ClassDeclaration(this);
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
    }

    @Override
    protected ClassDeclaration self() {
        return this;
    }
}
