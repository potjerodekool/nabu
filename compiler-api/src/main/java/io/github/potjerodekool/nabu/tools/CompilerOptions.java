package io.github.potjerodekool.nabu.tools;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Compiler options.
 */
public interface CompilerOptions {

    Optional<String> getOption(CompilerOption compilerOption);

    <T> Optional<T> getOption(CompilerOption compilerOption, Class<T> type);

    <T> T getOption(CompilerOption compilerOption, T defaultValue, Class<T> type);

    Optional<Path> getPathOption(CompilerOption compilerOptions);

    boolean hasOption(CompilerOption compilerOption);

    default Optional<Path> getClassOutput() {
        return getPathOption(CompilerOption.CLASS_OUTPUT);
    }

    JavaVersion getTargetVersion();

    void forEach(final BiConsumer<CompilerOption, String> consumer);

    Stream<Map.Entry<CompilerOption, String>> stream();

    class CompilerOptionsBuilder {
        private final Map<CompilerOption, String> options = new HashMap<>();

        public CompilerOptionsBuilder option(final CompilerOption compilerOption,
                                             final String value) {
            this.options.put(compilerOption, value);
            return this;
        }

        public CompilerOptions build() {
            return new CompilerOptionsImpl(options);
        }
    }

    record CompilerOptionsImpl(Map<CompilerOption, String> options) implements CompilerOptions {

        @Override
        public Optional<String> getOption(final CompilerOption compilerOption) {
            if (options.containsKey(compilerOption)) {
                return Optional.ofNullable(options.get(compilerOption));
            } else {
                return Optional.empty();
            }
        }

        @Override
        public <T> T getOption(final CompilerOption compilerOption, final T defaultValue, final Class<T> type) {
            return getOption(compilerOption, type)
                    .orElse(defaultValue);
        }

        @Override
        public <T> Optional<T> getOption(final CompilerOption compilerOption,
                                         final Class<T> type) {
            return getOption(compilerOption)
                    .map(value -> convertValue(value, type));
        }

        private <T> T convertValue(final String value,
                                   final Class<T> targetType) {
            if (targetType == String.class) {
                return (T) value;
            } else if (targetType == Boolean.class) {
                return value.isEmpty()
                        ? (T) Boolean.FALSE
                        : (T) Boolean.valueOf(value);
            } else if (targetType == Path.class) {
                if (!value.isEmpty()) {
                    return (T) Paths.get(value);
                }
            }
            return null;
        }


        @Override
        public Optional<Path> getPathOption(final CompilerOption compilerOptions) {
            return getOption(compilerOptions)
                    .map(this::valueAsPath);
        }

        @Override
        public boolean hasOption(final CompilerOption compilerOption) {
            return this.options.containsKey(compilerOption);
        }

        private Path valueAsPath(final String value) {
            return value != null && !value.isEmpty()
                    ? Paths.get(value)
                    : null;
        }

        private <V> V getOptionalOrDefault(final CompilerOption compilerOption,
                                           final V defaultValue,
                                           final Function<String, V> converter) {
            final var valueOptional = getOption(compilerOption);
            return valueOptional.map(converter)
                    .orElse(defaultValue);
        }

        @Override
        public JavaVersion getTargetVersion() {
            return getOptionalOrDefault(
                    CompilerOption.TARGET_VERSION,
                    JavaVersion.MINIMAL_VERSION,
                    JavaVersion::parseFromName
            );
        }

        @Override
        public void forEach(final BiConsumer<CompilerOption, String> consumer) {
            this.options.forEach(consumer);
        }

        @Override
        public Stream<Map.Entry<CompilerOption, String>> stream() {
            return this.options.entrySet().stream();
        }
    }

}

