package io.github.potjerodekool.nabu.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.Function;

public class LoggerFactory {

    private static Function<String, Logger> provider = (name) -> SystemLogger.INSTANCE;

    public static void setProvider(final Function<String, Logger> provider) {
        LoggerFactory.provider = provider;
    }

    static Logger getLogger(final String name) {
        return provider.apply(name);
    }
}

class DevNullLogger implements Logger {

    static final DevNullLogger INSTANCE = new DevNullLogger();

    private DevNullLogger() {
    }

    @Override
    public void log(final LogLevel level,
                    final String message,
                    final Throwable exception) {
    }
}

class SystemLogger implements Logger {

    static final SystemLogger INSTANCE = new SystemLogger();

    private SystemLogger() {
    }

    @Override
    public void log(final LogLevel level,
                    final String message,
                    final Throwable exception) {
        if (level == LogLevel.ERROR) {
            System.err.println(message);

            if (exception != null) {
                final var stringWriter = new StringWriter();
                new PrintWriter(stringWriter);
                exception.printStackTrace(new PrintWriter(stringWriter));
                System.err.println(stringWriter.getBuffer().toString());
            }
        } else {
            System.out.println(message);
        }
    }
}