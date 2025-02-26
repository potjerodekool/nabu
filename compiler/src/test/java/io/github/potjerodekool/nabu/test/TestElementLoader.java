package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.ClassBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.PackageElementBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.NestingKind;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.SymbolTable;
import io.github.potjerodekool.nabu.compiler.resolve.TypesImpl;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ImportScope;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class TestElementLoader implements ClassElementLoader {

    private final TypesImpl types = new TypesImpl(this, new SymbolTable());
    private final Map<String, TypeElement> classes = new HashMap<>();

    @Override
    public TypeElement resolveClass(final String name) {
        var clazz = classes.get(name);

        if (clazz == null) {
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

            clazz = new ClassBuilder()
                    .kind(ElementKind.CLASS)
                    .nestingKind(NestingKind.TOP_LEVEL)
                    .name(simpleName)
                    .enclosingElement(owner)
                    .build();


            classes.put(name, clazz);
        }

        return clazz;
    }

    @Override
    public TypesImpl getTypes() {
        return types;
    }

    @Override
    public void postInit() {
    }

    @Override
    public void addClassPathEntry(final Path path) {
    }

    @Override
    public PackageElement findOrCreatePackage(final String packageName) {
        throw new TodoException();
    }

    @Override
    public void importJavaLang(final ImportScope importScope) {
    }

    @Override
    public void close() {
    }

    public void add(final TypeElement classSymbol) {
        classes.put(classSymbol.getQualifiedName(), classSymbol);
    }
}
