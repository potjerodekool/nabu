package io.github.potjerodekool.nabu.compiler.lang.support.kotlin;

import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.PackageDeclaration;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.NestingKind;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.RuleNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class KotlinCompilerVisitor extends KotlinParserBaseVisitor<Object> {

    private final FileObject fileObject;
    private boolean isTopLevel = true;

    public KotlinCompilerVisitor(final FileObject fileObject) {
        this.fileObject = fileObject;
    }

    @Override
    public Object visitChildren(final RuleNode node) {
        throw new TodoException(node.getClass().getName());
    }

    @Override
    public Object visitKotlinFile(final KotlinParser.KotlinFileContext ctx) {
        final var preamble = (Preamble) ctx.preamble().accept(this);
        final var anySemi = ctx.anysemi().stream()
                .map(a -> a.accept(this))
                .toList();
        failOnNonNullOrEmpty(anySemi);

        final var topLevels = ctx.topLevelObject().stream()
                .map(topLevel -> (Tree) topLevel.accept(this))
                .toList();

        final var packageDeclaration = preamble.packageDeclaration();

        final List<Tree> declarations;

        if (packageDeclaration != null) {
            declarations = new ArrayList<>(topLevels.size() + 1);
            declarations.add(packageDeclaration);
            declarations.addAll(topLevels);
        } else {
            declarations = topLevels;
        }

        return TreeMaker.compilationUnit(
                fileObject,
                Collections.emptyList(),
                declarations,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitPreamble(final KotlinParser.PreambleContext ctx) {
        final var annotations = accept(ctx.fileAnnotations(), Collections::emptyList);
        failOnNonNullOrEmpty(annotations);
        final var packageHeader = (PackageDeclaration) ctx.packageHeader().accept(this);
        final var importList = accept(ctx.importList(), Collections::emptyList);
        failOnNonNullOrEmpty(importList);

        return new Preamble(
                packageHeader
        );
    }

    @Override
    public Object visitPackageHeader(final KotlinParser.PackageHeaderContext ctx) {
        final var modifiers = accept(ctx.modifierList());
        failOnNonNullOrEmpty(modifiers);
        final var identifier = (ExpressionTree) ctx.identifier().accept(this);

        return TreeMaker.packageDeclaration(
                List.of(),
                identifier,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitIdentifier(final KotlinParser.IdentifierContext ctx) {

        return ctx.simpleIdentifier().stream()
                .map(it -> (ExpressionTree) it.accept(this))
                .reduce((first, second) -> TreeMaker.fieldAccessExpressionTree(
                        first,
                        (IdentifierTree) second,
                        first.getLineNumber(),
                        first.getColumnNumber()
                )
                ).get();
    }

    @Override
    public Object visitSimpleIdentifier(final KotlinParser.SimpleIdentifierContext ctx) {
        return TreeMaker.identifier(
                ctx.getText(),
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitImportList(final KotlinParser.ImportListContext ctx) {
        return ctx.importHeader().stream()
                .map(importHeader -> importHeader.accept(this))
                .toList();
    }

    @Override
    public Object visitTopLevelObject(final KotlinParser.TopLevelObjectContext ctx) {
        return ctx.getChild(0).accept(this);
    }

    @Override
    public Object visitClassDeclaration(final KotlinParser.ClassDeclarationContext ctx) {
        final var modifiers = accept(ctx.modifierList(), Modifiers::new);
        final var identifier = (IdentifierTree) ctx.simpleIdentifier().accept(this);
        final var kind = "class".equals(ctx.kind.getText())
                ? Kind.CLASS
                : Kind.INTERFACE;

        final var nestingKind = isTopLevel ? NestingKind.TOP_LEVEL
                : NestingKind.MEMBER;

        return TreeMaker.classDeclaration(
                kind,
                nestingKind,
                modifiers,
                identifier.getName(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    private void failOnNonNullOrEmpty(final Object o) {
        if (o instanceof Collection<?> collection) {
            if (!collection.isEmpty()) {
                throw new TodoException();
            }
        } else if (o != null) {
            throw new TodoException();
        }
    }

    private <T> T accept(final ParserRuleContext context) {
        return accept(context, () -> null);
    }

    private <T> T accept(final ParserRuleContext context,
                         final Supplier<T> defaultValueSupplier) {
        if (context == null) {
            return defaultValueSupplier.get();
        } else {
            return (T) context.accept(this);
        }
    }
}

record Preamble(PackageDeclaration packageDeclaration) {

}