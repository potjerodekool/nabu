package io.github.potjerodekool.nabu.compiler.annotation.tools;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.tools.FileObject;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.JavaFileObject;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class JavacFileObject implements JavaFileObject {

    private final ModuleSymbol moduleSymbol;
    private final String packageName;
    private final Kind kind;
    private FileObject delegate;

    public JavacFileObject(final ModuleSymbol moduleSymbol,
                           final String packageName,
                           final Kind kind) {
        this.moduleSymbol = moduleSymbol;
        this.packageName = packageName;
        this.kind = kind != null ? kind : Kind.SOURCE;
    }

    public JavacFileObject(final ModuleSymbol moduleSymbol,
                           final String packageName,
                           final Kind kind,
                           final FileObject delegate) {
        this.moduleSymbol = moduleSymbol;
        this.packageName = packageName;
        this.kind = kind;
        this.delegate = delegate;
    }

    public ModuleSymbol getModuleSymbol() {
        return moduleSymbol;
    }

    public String getPackageName() {
        return packageName;
    }

    @Override
    public Kind getKind() {
        return kind;
    }

    @Override
    public boolean isNameCompatible(final String simpleName, final Kind kind) {
        return this.kind == kind;
    }

    @Override
    public NestingKind getNestingKind() {
        return NestingKind.TOP_LEVEL;
    }

    @Override
    public Modifier getAccessLevel() {
        return Modifier.PUBLIC;
    }

    @Override
    public URI toUri() {
        final var encodedPackage = URLEncoder.encode(packageName, StandardCharsets.UTF_8);
        return URI.create("string:///generated/" + encodedPackage + "." + kind.extension);
    }

    @Override
    public String getName() {
        final var modulePart = moduleSymbol != null ? moduleSymbol.getSimpleName() + "/" : "";
        return modulePart + packageName + "." + kind.extension;
    }

    @Override
    public InputStream openInputStream() throws IOException {
        if (delegate != null) {
            return delegate.openInputStream();
        }
        throw new IOException("No content available for " + getName());
    }

    @Override
    public OutputStream openOutputStream() throws IOException {
        if (delegate != null) {
            return delegate.openOutputStream();
        }
        throw new IOException("Cannot write to generated file object: " + getName());
    }

    @Override
    public Reader openReader(final boolean ignoreEncodingErrors) throws IOException {
        if (delegate != null) {
            return delegate.openReader(ignoreEncodingErrors);
        }
        throw new IOException("No content available for " + getName());
    }

    @Override
    public CharSequence getCharContent(final boolean ignoreEncodingErrors) throws IOException {
        if (delegate != null) {
            try (final var reader = delegate.openReader(ignoreEncodingErrors)) {
                final var sb = new StringBuilder();
                final var buffer = new char[4096];
                int len;
                while ((len = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, len);
                }
                return sb.toString();
            }
        }
        throw new IOException("No content available for " + getName());
    }

    @Override
    public Writer openWriter() throws IOException {
        if (delegate != null) {
            return delegate.openWriter();
        }
        throw new IOException("Cannot write to generated file object: " + getName());
    }

    @Override
    public long getLastModified() {
        return 0;
    }

    @Override
    public boolean delete() {
        return false;
    }
}
