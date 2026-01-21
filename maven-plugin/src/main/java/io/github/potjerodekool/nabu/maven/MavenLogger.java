package io.github.potjerodekool.nabu.maven;

import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
import org.apache.maven.plugin.logging.Log;

class MavenLogger implements Logger {

    private final Log log;
    private final boolean showWarnings;
    private final boolean showDebugs;

    public MavenLogger(final Log log,
                       final boolean showWarnings,
                       final boolean showDebugs) {
        this.log = log;
        this.showWarnings = showWarnings;
        this.showDebugs = showDebugs;
    }

    @Override
    public void log(final LogLevel level,
                    final String message,
                    final Throwable exception) {
        switch (level) {
            case INFO -> this.log.info(message, exception);
            case DEBUG -> {
                if (showDebugs) {
                    this.log.debug(message, exception);
                }
            }
            case ERROR -> this.log.error(message, exception);
            case WARN -> {
                if (showWarnings) {
                    this.log.warn(message, exception);
                }
            }
        }
    }
}
