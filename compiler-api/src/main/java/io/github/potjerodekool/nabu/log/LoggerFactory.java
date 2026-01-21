package io.github.potjerodekool.nabu.log;

import java.util.function.Function;

public class LoggerFactory {

    private static Function<String, Logger> provider = (name) -> DevNullLogger.INSTANCE;

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
