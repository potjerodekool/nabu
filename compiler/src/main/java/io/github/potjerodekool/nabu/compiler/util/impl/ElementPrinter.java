package io.github.potjerodekool.nabu.compiler.util.impl;

import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.type.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class ElementPrinter implements ElementVisitor<Object, Object>,
        TypeVisitor<Object, Object> {

    private final BufferedWriter writer;
    private final StringBuilder tabs = new StringBuilder();
    private static final String TAB = "    ";
    private static final int TAB_SIZE = TAB.length();
    private boolean atStartOfLine = true;

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
        final var parameters = executableElement.getParameters();
        final var lastParamIndex = parameters.size() -1;

        if (!executableElement.getModifiers().isEmpty()) {
            printModifiers(executableElement.getModifiers());
            print(" ");
        }

        print("fun ");
        print(executableElement.getSimpleName());
        print("(");

        for (var i = 0; i < parameters.size(); i++) {
            parameters.get(i).accept(this, o);
            if (i < lastParamIndex) {
                print(", ");
            }
        }

        print("): ");
        executableElement.getReturnType().accept(this, o);
        printLn(" {");
        newLine();
        printLn("}");

        return null;
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

        incrementTabs();

        typeElement.getEnclosedElements().forEach(enclosedElement -> {
            enclosedElement.accept(this, o);
            newLine();
        });

        decrementTabs();

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
        if (!variableElement.getModifiers().isEmpty()) {
            printModifiers(variableElement.getModifiers());
            print(" ");
        }

        print(variableElement.getSimpleName());
        print(" : ");

        variableElement.asType().accept(this, o);

        if (variableElement.getKind() == ElementKind.FIELD || variableElement.getKind() == ElementKind.ENUM_CONSTANT) {
            printLn(";");
        }

        return null;
    }

    @Override
    public Object visitModule(final ModuleElement moduleElement, final Object o) {
        return ElementVisitor.super.visitModule(moduleElement, o);
    }

    private void print(final String text) {
        try {
            if (!"\n".equals(text)) {
                insertTabsIfNeeded();
            }

            writer.write(text);
            detectNewLine(text);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void printLn(final String s) {
        insertTabsIfNeeded();
        print(s);
        newLine();
    }

    private void newLine() {
        try {
            writer.newLine();
            this.atStartOfLine = true;
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Object visitDeclaredType(final DeclaredType declaredType, final Object param) {
        final var className = declaredType.asTypeElement().getQualifiedName();
        print(className);
        return null;
    }

    @Override
    public Object visitNoType(final NoType noType, final Object param) {
        if (noType.getKind() == TypeKind.VOID) {
            print("void");
            return null;
        }

        return TypeVisitor.super.visitNoType(noType, param);
    }

    @Override
    public Object visitUnknownType(final TypeMirror typeMirror, final Object param) {
        throw new TodoException(typeMirror.toString());
    }


    private void detectNewLine(final String text) {
        if (text.contains("\n")) {
            atStartOfLine = true;
        }
    }

    private void insertTabsIfNeeded() {
        if (atStartOfLine) {
            if (!tabs.isEmpty()) {
                try {
                    writer.write(tabs.toString());
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                }
            }
            atStartOfLine = false;
        }
    }

    private void incrementTabs() {
        this.tabs.append(TAB);
    }

    private void decrementTabs() {
        final int length = tabs.length();
        this.tabs.delete(length - TAB_SIZE, length);
    }
}
