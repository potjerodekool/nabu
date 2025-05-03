package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.diagnostic.DelegateDiagnosticListener;
import io.github.potjerodekool.nabu.compiler.diagnostic.Diagnostic;
import io.github.potjerodekool.nabu.compiler.diagnostic.DiagnosticListener;

import java.util.ArrayList;
import java.util.List;

public class ErrorCapture extends DelegateDiagnosticListener {

    private final List<Diagnostic> errors = new ArrayList<>();

    public ErrorCapture(final DiagnosticListener delegate) {
        super(delegate);
    }

    @Override
    public void report(final Diagnostic diagnostic) {
        super.report(diagnostic);
        if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
            errors.add(diagnostic);
        }
    }

    public List<Diagnostic> getErrors() {
        return errors;
    }

    public int getErrorCount() {
        return errors.size();
    }
}