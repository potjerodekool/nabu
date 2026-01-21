package io.github.potjerodekool.nabu.compiler.lang.support.java;

import io.github.potjerodekool.nabu.Java20Parser;
import io.github.potjerodekool.nabu.Java20ParserBaseVisitor;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.lang.model.element.Directive;
import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.TodoException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class JavaModuleParser extends Java20ParserBaseVisitor<Object> {

    private final SymbolTable symbolTable;
    private final ModuleSymbol moduleSymbol;
    private final List<ModuleElement.RequiresDirective> requiresDirectives = new ArrayList<>();
    private final List<ModuleElement.ExportsDirective> exportsDirectives = new ArrayList<>();

    private JavaModuleParser(final SymbolTable symbolTable,
                             final ModuleSymbol moduleSymbol) {
        this.symbolTable = symbolTable;
        this.moduleSymbol = moduleSymbol;
    }

    public static void parse(final FileObject fileObject,
                             final SymbolTable symbolTable,
                             final ModuleSymbol moduleSymbol) {
        try (var inputstream = fileObject.openInputStream()) {
            final var compilationUnit = JavaCompilerParser.parse(inputstream);
            final var parser = new JavaModuleParser(symbolTable, moduleSymbol);
            compilationUnit.accept(parser);
            moduleSymbol.setExports(parser.exportsDirectives);
            moduleSymbol.setRequires(parser.requiresDirectives);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Object visitModuleDirective(final Java20Parser.ModuleDirectiveContext ctx) {
        final var directiveType = ctx.directiveType.getText();

        final var modules = ctx.moduleName().stream()
                .map(it -> (String) it.accept(this))
                .map(this.symbolTable::getModule)
                .map(module -> (ModuleElement) module)
                .toList();

        if ("requires".equals(directiveType)) {
            final var flags = ctx.requiresModifier().stream()
                    .map(it -> (Directive.RequiresFlag) it.accept(this))
                    .collect(Collectors.toSet());
            final var module = modules.getFirst();
            this.requiresDirectives.add(new Directive.RequiresDirective(module, flags));
        } else if ("exports".equals(directiveType)) {
            final var packageName = (String) ctx.packageName().accept(this);
            final var packageSymbol = symbolTable.lookupPackage(
                    moduleSymbol,
                    packageName
            );
            this.exportsDirectives.add(new Directive.ExportsDirective(packageSymbol, modules));
        }

        return null;
    }

    @Override
    public Object visitPackageName(final Java20Parser.PackageNameContext ctx) {
        final var identifier = (String) ctx.identifier().accept(this);

        if (ctx.packageName() != null) {
            final var packageName = (String) ctx.packageName().accept(this);
            return identifier + "." + packageName;
        } else {
            return identifier;
        }
    }

    @Override
    public Object visitIdentifier(final Java20Parser.IdentifierContext ctx) {
        if (ctx.Identifier() != null) {
            return ctx.Identifier().getText();
        } else {
            throw new TodoException();
        }
    }

    @Override
    public Object visitRequiresModifier(final Java20Parser.RequiresModifierContext ctx) {
        final var text = ctx.getText();

        if ("transitive".equals(text)) {
            return Directive.RequiresFlag.TRANSITIVE;
        } else if ("static".equals(text)) {
            return Directive.RequiresFlag.STATIC_PHASE;
        } else {
            throw new TodoException();
        }
    }
}
