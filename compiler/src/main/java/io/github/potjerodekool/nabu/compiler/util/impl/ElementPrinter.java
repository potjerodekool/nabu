package io.github.potjerodekool.nabu.compiler.util.impl;

import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.tools.TodoException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class ElementPrinter implements ElementVisitor<Object, Object> {

    private final BufferedWriter writer;

    public static void print(final Element element,
                             final Writer writer) throws IOException {
        final var printer = new ElementPrinter(writer);
        element.accept(printer, null);
        printer.writer.flush();
    }

    private ElementPrinter(final Writer writer) {
        if (writer instanceof BufferedWriter bufferedWriter) {
            this.writer = bufferedWriter;
        } else {
            this.writer = new BufferedWriter(writer);
        }
    }

    @Override
    public Object visitUnknown(final Element e, final Object o) {
        throw new TodoException();
    }

    @Override
    public Object visitExecutable(final ExecutableElement executableElement, final Object o) {
        return ElementVisitor.super.visitExecutable(executableElement, o);
    }

    @Override
    public Object visitTypeParameter(final TypeParameterElement typeParameterElement, final Object o) {
        return ElementVisitor.super.visitTypeParameter(typeParameterElement, o);
    }

    @Override
    public Object visitType(final TypeElement typeElement, final Object o) {
        final var modifiers = typeElement.getModifiers();

        if (!modifiers.isEmpty()) {
            printModifiers(modifiers);
            print(" ");
        }

        switch (typeElement.getKind()) {
            case CLASS -> print("class");
            case INTERFACE -> print("interface");
        }

        print(" " + typeElement.getSimpleName() + " ");

        printLn("{");
        printLn("}");

        return null;
    }

    private void printModifiers(final Set<Modifier> modifiers) {
        final var sorted = modifiers.stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        int i = 0;
        for (final var modifier : sorted) {
            if (i > 0) {
                print(" ");
            }

            final var name = modifier.name().toLowerCase();
            print(name);
            i++;
        }
    }

    @Override
    public Object visitPackage(final PackageElement packageElement, final Object o) {
        return ElementVisitor.super.visitPackage(packageElement, o);
    }

    @Override
    public Object visitVariable(final VariableElement variableElement, final Object o) {
        return ElementVisitor.super.visitVariable(variableElement, o);
    }

    @Override
    public Object visitModule(final ModuleElement moduleElement, final Object o) {
        return ElementVisitor.super.visitModule(moduleElement, o);
    }

    private void print(final String s) {
        try {
            writer.write(s);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void printLn(final String s) {
        print(s);
        newLine();
    }

    private void newLine() {
        try {
            writer.newLine();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }
}
