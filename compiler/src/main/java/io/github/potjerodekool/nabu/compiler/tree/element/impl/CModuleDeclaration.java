package io.github.potjerodekool.nabu.compiler.tree.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.DirectiveTree;
import io.github.potjerodekool.nabu.compiler.tree.element.ModuleDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.impl.CTree;

import java.util.ArrayList;
import java.util.List;

public class CModuleDeclaration extends CTree implements ModuleDeclaration {

    private final ModuleKind kind;
    private final ExpressionTree identifier;
    private final List<DirectiveTree> directives;
    private final List<AnnotationTree> annotations;
    private ModuleSymbol moduleSymbol;

    public CModuleDeclaration(final int lineNumber,
                              final int columnNumber,
                              final ModuleKind kind,
                              final ExpressionTree identifier,
                              final List<DirectiveTree> directives,
                              final List<AnnotationTree> annotations) {
        super(lineNumber, columnNumber);
        this.kind = kind;
        this.identifier = identifier;
        this.directives = new ArrayList<>(directives);
        this.annotations = new ArrayList<>(annotations);
    }

    @Override
    public List<AnnotationTree> getAnnotations() {
        return annotations;
    }

    @Override
    public ModuleKind getKind() {
        return kind;
    }

    @Override
    public ExpressionTree getIdentifier() {
        return identifier;
    }

    @Override
    public List<? extends DirectiveTree> getDirectives() {
        return directives;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitModuleDeclaration(this, param);
    }

    @Override
    public ModuleSymbol getModuleSymbol() {
        return moduleSymbol;
    }

    public void setModuleSymbol(final ModuleSymbol moduleSymbol) {
        this.moduleSymbol = moduleSymbol;
    }
}
