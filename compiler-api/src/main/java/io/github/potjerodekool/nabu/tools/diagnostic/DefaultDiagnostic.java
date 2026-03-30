package io.github.potjerodekool.nabu.tools.diagnostic;

import io.github.potjerodekool.nabu.tools.FileObject;

import java.util.Locale;

public class DefaultDiagnostic implements Diagnostic {

    private final Kind kind;

    private final CharSequence message;

    private final FileObject fileObject;

    private final Integer lineNumber;
    private final Integer columnNumber;

    public DefaultDiagnostic(final Kind kind,
                             final CharSequence message,
                             final FileObject fileObject,
                             final Integer lineNumber,
                             final Integer columnNumber) {
        this.kind = kind;
        this.message = message;
        this.fileObject = fileObject;
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }

    public DefaultDiagnostic(final Kind kind,
                             final CharSequence message,
                             final FileObject fileObject) {
        this(kind, message, fileObject, null, null);
    }

    @Override
    public Kind getKind() {
        return kind;
    }

    @Override
    public CharSequence getMessage(final Locale locale) {
        return message;
    }

    @Override
    public FileObject getFileObject() {
        return fileObject;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }

    public Integer getColumnNumber() {
        return columnNumber;
    }
}
