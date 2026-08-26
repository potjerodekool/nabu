package io.github.potjerodekool.nabu.tools.diagnostic;

import io.github.potjerodekool.nabu.tools.FileObject;

import java.util.Locale;

/**
 * An event of the compiler.
 */
public interface Diagnostic {

    /**
     * @return Returns the kind of the diagnostic.
     */
    Kind getKind();

    /**
     * @param locale A locale.
     * @return Returns the diagnostic message in the given locale.
     * If the given locale isn't supported than the returned message should be in English.
     */
    CharSequence getMessage(Locale locale);

    /**
     * @return Return the FileObject associated with this Diagnostic.
     */
    FileObject getFileObject();

    /**
     * @return Returns the line number this diagnostic refers to or null.
     */
    Integer getLineNumber();

    /**
     * @return Returns the column number this diagnostic refers to or null.
     */
    Integer getColumnNumber();

    /** Enumeration of the Diagnostic kinds */
    enum Kind {
        /** Error diagnostic like compilation errors */
        ERROR,
        /** Warnings */
        WARN,
        MANDATORY_WARNING,
        NOTE,
        OTHER
    }
}
