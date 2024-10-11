package io.github.potjerodekool.nabu.compiler.diagnostic;

import io.github.potjerodekool.nabu.compiler.FileObject;

import java.util.Locale;

public interface Diagnostic {

    Kind getKind();

    String getMessage(Locale locale);

    FileObject getFileObject();

    enum Kind {
        ERROR,
        WARN,
        INFO
    }
}
