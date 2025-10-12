package io.github.potjerodekool.nabu.tools.diagnostic;

public class DelegateDiagnosticListener implements DiagnosticListener {

    private DiagnosticListener delegate;

    public DelegateDiagnosticListener(final DiagnosticListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void report(final Diagnostic diagnostic) {
        delegate.report(diagnostic);
    }

    public void setListener(final DiagnosticListener listener) {
        this.delegate = listener;
    }
}
