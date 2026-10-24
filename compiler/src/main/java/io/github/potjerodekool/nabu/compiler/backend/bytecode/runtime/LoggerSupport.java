package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.util.ResourceBundle;

/**
 * {@code System.getLogger} in a native executable: the loggers log nothing ({@code isLoggable} is false), so
 * JDK code that logs at debug or trace level (for example {@code ProcessBuilder.start}) runs without a logging
 * framework. {@code System.getLogger} is redirected here (see {@code RuntimeCalls}).
 */
public final class LoggerSupport {

    private LoggerSupport() {
    }

    public static System.Logger getLogger(final String name) {
        return new SilentLogger(name);
    }

    private static final class SilentLogger implements System.Logger {
        private final String name;

        SilentLogger(final String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isLoggable(final System.Logger.Level level) {
            return false;
        }

        @Override
        public void log(final System.Logger.Level level, final ResourceBundle bundle, final String msg,
                        final Throwable thrown) {
        }

        @Override
        public void log(final System.Logger.Level level, final ResourceBundle bundle, final String format,
                        final Object... params) {
        }
    }
}
