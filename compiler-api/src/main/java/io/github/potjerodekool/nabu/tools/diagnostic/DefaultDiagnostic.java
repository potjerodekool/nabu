package io.github.potjerodekool.nabu.tools.diagnostic;

import io.github.potjerodekool.nabu.tools.FileObject;

import java.util.Locale;

public class DefaultDiagnostic implements Diagnostic {

    private final Kind kind;

    private final String message;

    private final FileObject fileObject;

    public DefaultDiagnostic(final Kind kind,
                             final String message,
                             final FileObject fileObject) {
        this.kind = kind;
        this.message = message;
        this.fileObject = fileObject;
    }

    @Override
    public Kind getKind() {
        return kind;
    }

    @Override
    public String getMessage(final Locale locale) {
        return message;
    }

    @Override
    public FileObject getFileObject() {
        return fileObject;
    }
}
