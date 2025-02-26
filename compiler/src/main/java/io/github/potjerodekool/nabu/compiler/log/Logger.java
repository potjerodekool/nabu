package io.github.potjerodekool.nabu.compiler.log;

public interface Logger {

    static Logger getLogger(final String name) {
        return LoggerFactory.getLogger(name);
    }

    default void log(LogLevel level, String message) {
        log(level, message, null);
    }

    void log(LogLevel level, String message, Throwable exception);
}
