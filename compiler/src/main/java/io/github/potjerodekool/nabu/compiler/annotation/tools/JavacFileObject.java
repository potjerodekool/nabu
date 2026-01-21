package io.github.potjerodekool.nabu.compiler.annotation.tools;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.tools.TodoException;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.JavaFileObject;
import java.io.*;
import java.net.URI;

public class JavacFileObject implements JavaFileObject {

    private final ModuleSymbol moduleSymbol;
    private final Kind kind;

    public JavacFileObject(final ModuleSymbol moduleSymbol,
                           final String packageName,
                           final Kind kind) {
        this.moduleSymbol = moduleSymbol;
        this.kind = kind;
    }

    @Override
    public Kind getKind() {
        return kind;
    }

    @Override
    public boolean isNameCompatible(final String simpleName, final Kind kind) {
        if (this.kind != kind) {
            return false;
        }

        throw new TodoException();
    }

    @Override
    public NestingKind getNestingKind() {
        return null;
    }

    @Override
    public Modifier getAccessLevel() {
        throw new TodoException();
    }

    @Override
    public URI toUri() {
        throw new TodoException();
    }

    @Override
    public String getName() {
        throw new TodoException();
    }

    @Override
    public InputStream openInputStream() throws IOException {
        throw new TodoException();
    }

    @Override
    public OutputStream openOutputStream() throws IOException {
        throw new TodoException();
    }

    @Override
    public Reader openReader(final boolean ignoreEncodingErrors) throws IOException {
        throw new TodoException();
    }

    @Override
    public CharSequence getCharContent(final boolean ignoreEncodingErrors) throws IOException {
        throw new TodoException();
    }

    @Override
    public Writer openWriter() throws IOException {
        throw new TodoException();
    }

    @Override
    public long getLastModified() {
        throw new TodoException();
    }

    @Override
    public boolean delete() {
        throw new TodoException();
    }
}
