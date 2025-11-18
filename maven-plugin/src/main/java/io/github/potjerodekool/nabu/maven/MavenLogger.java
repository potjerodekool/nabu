package io.github.potjerodekool.nabu.maven;

import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
import org.apache.maven.plugin.logging.Log;

class MavenLogger implements Logger {

    private final Log log;

    public MavenLogger(final Log log) {
        this.log = log;
    }

    @Override
    public void log(final LogLevel level, final String message, final Throwable exception) {
        switch (level) {
            case INFO -> this.log.info(message, exception);
            case DEBUG -> this.log.debug(message, exception);
            case ERROR -> this.log.error(message, exception);
            case WARN -> this.log.warn(message, exception);
        }
    }
}
