package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.tools.diagnostic.DelegateDiagnosticListener;
import io.github.potjerodekool.nabu.tools.diagnostic.Diagnostic;
import io.github.potjerodekool.nabu.tools.diagnostic.DiagnosticListener;

import java.util.ArrayList;
import java.util.List;

public class CompilerDiagnosticListener extends DelegateDiagnosticListener {

    private final List<Diagnostic> errors = new ArrayList<>();

    public CompilerDiagnosticListener(final DiagnosticListener delegate) {
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