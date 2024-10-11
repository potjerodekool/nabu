package io.github.potjerodekool.nabu.compiler.diagnostic;

public class DelegateDiagnosticListener implements DiagnosticListener {

    private final DiagnosticListener delegate;

    public DelegateDiagnosticListener(final DiagnosticListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void report(final Diagnostic diagnostic) {
        delegate.report(diagnostic);
    }
}
