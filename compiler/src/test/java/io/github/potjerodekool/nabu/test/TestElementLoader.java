package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.ClassBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.PackageElementBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.NestingKind;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.SymbolTable;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;

public class TestElementLoader extends AsmClassElementLoader implements ClassElementLoader {

    private final SymbolTable symbolTable;

    public TestElementLoader(final SymbolTable symbolTable) {
        super(symbolTable);
        this.symbolTable = symbolTable;
    }

    @Override
    public TypeElement loadClass(final String name) {
        final var typeElement = super.loadClass(name);

        if (typeElement != null) {
            return typeElement;
        }

        var sep = name.lastIndexOf("/");

        if (sep < 0) {
            sep = name.lastIndexOf(".");
        }

        PackageElement owner = null;
        final String simpleName;

        if (sep > 0) {
            owner = PackageElementBuilder.createFromName(name.substring(0, sep));
            simpleName = name.substring(sep + 1);
        } else {
            simpleName = name;
        }

        final var clazz = new ClassBuilder()
                .kind(ElementKind.CLASS)
                .nestingKind(NestingKind.TOP_LEVEL)
                .name(simpleName)
                .enclosingElement(owner)
                .build();

        final var internalName = name.replace('.', '/');
        symbolTable.addClassSymbol(internalName, clazz);

        return clazz;
    }
}
