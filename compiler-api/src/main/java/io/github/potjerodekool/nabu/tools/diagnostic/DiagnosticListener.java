package io.github.potjerodekool.nabu.tools.diagnostic;

/**
 * A diagnostic listener to listen to events of the compiler.
 */
public interface DiagnosticListener {

    void report(Diagnostic diagnostic);
}
