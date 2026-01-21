package io.github.potjerodekool.nabu.tools.diagnostic;

import java.io.PrintStream;

public class ConsoleDiagnosticListener implements DiagnosticListener {

    @Override
    public void report(final Diagnostic diagnostic) {
        final var fileObject = diagnostic.getFileObject();
        final var fileName = fileObject != null
                ? fileObject.getFileName()
                : null;

        switch (diagnostic.getKind()) {
            case WARN, MANDATORY_WARNING, ERROR -> print(
                    System.err,
                    diagnostic.getMessage(null),
                    fileName
            );
            default -> print(
                    System.out,
                    diagnostic.getMessage(null),
                    fileName
            );
        }
    }

    private void print(final PrintStream ps,
                       final CharSequence message,
                       final String fileName) {
        if (fileName != null) {
            ps.println(fileName + " : " + message);
        } else {
            ps.println(message);
        }
    }
}
