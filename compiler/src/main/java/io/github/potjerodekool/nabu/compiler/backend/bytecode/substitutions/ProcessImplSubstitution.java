package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

import io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.NativeProcess;

import java.io.IOException;
import java.util.Map;

/**
 * {@code ProcessImpl} needs the native process support of the JDK; a native executable starts a child process
 * through {@link NativeProcess} instead. Replaces {@code ProcessImpl.start} (see {@link JavaSubstitutions}).
 */
public final class ProcessImplSubstitution {

    private ProcessImplSubstitution() {
    }

    static Process start(final String[] cmdarray, final Map<String, String> environment, final String dir,
                         final ProcessBuilder.Redirect[] redirects, final boolean redirectErrorStream)
            throws IOException {
        return NativeProcess.start(cmdarray, environment, dir, redirects, redirectErrorStream);
    }
}
