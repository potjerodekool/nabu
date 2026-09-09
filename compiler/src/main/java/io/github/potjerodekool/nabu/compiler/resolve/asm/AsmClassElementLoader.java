package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ErrorSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.TypeSymbol;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.lang.model.element.PackageElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.scope.ImportScope;

public class AsmClassElementLoader implements ClassElementLoader, AutoCloseable {

    private final SymbolTable symbolTable;

    public AsmClassElementLoader(final CompilerContextImpl compilerContext) {
        compilerContext.put(new CompilerContextImpl.Key<>(), this);
        this.symbolTable = SymbolTable.getInstance(compilerContext);
    }

    @Override
    public TypeElement loadClass(final ModuleElement moduleElement,
                                 final String name) {
        final var flatName = Symbol.createFlatName(name);

        final var alreadyEntered = findEntered(flatName);

        if (alreadyEntered != null) {
            return alreadyEntered;
        }

        // Een geneste klasse die als dot-naam wordt opgevraagd
        // (bijv. "picocli.CommandLine.Command") moet via de binary-vorm
        // ("picocli.CommandLine$Command") worden geladen. De pakketdeel-
        // resolutie kan het laatste dot-segment anders niet van de enclosing
        // klasse onderscheiden.
        final var nested = loadNestedBinaryName(moduleElement, flatName);

        if (nested != null) {
            return nested;
        }

        return loadBinary(moduleElement, flatName);
    }

    private TypeElement findEntered(final String flatName) {
        var candidates = symbolTable.findClasses(flatName).stream()
                .filter(clazz -> !clazz.isError())
                .toList();

        if (candidates.isEmpty() && flatName.contains(".") && !flatName.contains("$")) {
            // Puntnotatie voor geneste klassen ("a.b.Outer.Inner"): probe de
            // punt-na-dolar converte's van rechts naar links.
            final var parts = flatName.split("\\.");
            var candidate = flatName;
            int dot;

            while ((dot = candidate.lastIndexOf('.')) > -1 && candidates.isEmpty()) {
                candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1);
                candidates = symbolTable.findClasses(candidate).stream()
                        .filter(clazz -> !clazz.isError())
                        .toList();
            }
        }

        if (candidates.isEmpty() && !flatName.contains(".") && !flatName.contains("$")) {
            // Kwalificatiesloze naam die (impliciet) tot java.lang behoort.
            final var javaLangCandidates = symbolTable.findClasses("java.lang." + flatName).stream()
                    .filter(clazz -> !clazz.isError())
                    .toList();

            if (!javaLangCandidates.isEmpty()) {
                return javaLangCandidates.stream()
                        .max(java.util.Comparator.comparingInt(clazz ->
                                (clazz.getSourceFile() != null ? 4 : 0)
                                        + (clazz.getClassFile() != null ? 2 : 0)
                                        + (clazz.getMembers() != null && !clazz.getMembers().elements().isEmpty() ? 1 : 0)))
                        .orElse(javaLangCandidates.getFirst());
            }

            return null;
        }

        if (candidates.isEmpty()) {
            return null;
        }

        // Kies de instantie die daadwerkelijk gebonden/voltooid is (bron- of
        // class-file, leden aanwezig). Voor waren er meerdere instanties
        // onder verschillende module-sleutels en werd willekeurig de eerste
        // (eventueel lege) gekozen.
        return candidates.stream()
                .max(java.util.Comparator.comparingInt(clazz ->
                        (clazz.getSourceFile() != null ? 4 : 0)
                                + (clazz.getClassFile() != null ? 2 : 0)
                                + (clazz.getMembers() != null && !clazz.getMembers().elements().isEmpty() ? 1 : 0)))
                .orElse(candidates.getFirst());
    }

    private TypeElement loadNestedBinaryName(final ModuleElement moduleElement,
                                             final String flatName) {
        if (flatName.contains("$")) {
            return null;
        }

        final var dotCount = flatName.length() - flatName.replace(".", "").length();

        if (dotCount < 2) {
            return null;
        }

        String candidate = flatName;
        int dot;

        while ((dot = candidate.lastIndexOf('.')) > -1) {
            candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1);

            final var loaded = loadBinary(moduleElement, candidate);

            if (isLoadable(loaded)) {
                return loaded;
            }
        }

        return null;
    }

    private boolean isLoadable(final TypeElement typeElement) {
        if (typeElement instanceof ClassSymbol classSymbol) {
            return classSymbol.getClassFile() != null
                    || classSymbol.getSourceFile() != null;
        }
        return typeElement != null;
    }

    private TypeElement loadBinary(final ModuleElement moduleElement,
                                   final String flatName) {
        final var module = moduleElement != null
                ? (ModuleSymbol) moduleElement
                : symbolTable.getUnnamedModule();

        final var packageName = resolvePackagePart(flatName);

        if (packageName == null) {
            return createError(flatName);
        }

        final var packageSymbol = symbolTable.lookupPackage(
                module,
                packageName
        );

        packageSymbol.complete();

        if (!packageSymbol.exists()) {
            return createError(flatName);
        }

        final var packageModule = packageSymbol.getModuleSymbol();

        if (flatName.contains("$")) {
            var previous = (TypeSymbol) packageSymbol;
            var symbol = previous;

            final var className = resolveShortPart(flatName);
            final var names = className.split("\\$");

            for (final String subName : names) {
                symbol = symbolTable.enterClass(
                        packageModule,
                        subName,
                        previous
                );
                previous = symbol;
            }

            return (TypeElement) symbol;
        } else {
            final var shortName = resolveShortPart(flatName);
            return symbolTable.enterClass(
                    packageModule,
                    shortName,
                    packageSymbol
            );
        }
    }

    private ErrorSymbol createError(final String name) {
        /*
        final var simpleNameStart = name.lastIndexOf('.') + 1;
        final var simpleName = name.substring(simpleNameStart);
        return new ErrorSymbol(simpleName);
        */
        return null;
    }

    private String resolvePackagePart(final String name) {
        final var sep = name.lastIndexOf('.');

        if (sep == -1) {
            return null;
        } else {
            return name.substring(0, sep);
        }
    }

    private String resolveShortPart(final String name) {
        final var sep = name.lastIndexOf('.') + 1;

        if (sep == 0) {
            return name;
        } else {
            return name.substring(sep);
        }
    }

    @Override
    public PackageElement findOrCreatePackage(final ModuleElement moduleElement,
                                              final String packageName) {
        return symbolTable.lookupPackage(moduleElement, packageName);
    }

    @Override
    public void importJavaLang(final ImportScope importScope) {
        final var javaLangPackage = symbolTable.lookupPackage(symbolTable.getUnnamedModule(), "java.lang");
        javaLangPackage.getMembers().elements().forEach(importScope::define);
    }

    @Override
    public void close() {
    }
}
