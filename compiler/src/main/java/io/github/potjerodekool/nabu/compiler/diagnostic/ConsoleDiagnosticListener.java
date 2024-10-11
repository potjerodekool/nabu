package io.github.potjerodekool.nabu.compiler.diagnostic;

import java.io.PrintStream;

public class ConsoleDiagnosticListener implements DiagnosticListener {

    @Override
    public void report(final Diagnostic diagnostic) {
        final var fileName = diagnostic.getFileObject().getFileName();

        switch (diagnostic.getKind()) {
            case INFO -> print(
                    System.out,
                    diagnostic.getMessage(null),
                    fileName
            );
            case WARN, ERROR -> print(
                    System.err,
                    diagnostic.getMessage(null),
                    fileName
            );
        }
    }

    private void print(final PrintStream ps,
                       final String message,
                       final String fileName) {
        ps.println(fileName + " : " + message);
    }
}
