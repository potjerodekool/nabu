package io.github.potjerodekool.nabu.compiler;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

public interface CompilerOptions {

    Optional<String> getOption(CompilerOption compilerOption);

    Optional<Path> getPathOption(CompilerOption compilerOptions);

    default Optional<Path> getTargetDirectory() {
        return getPathOption(CompilerOption.TARGET_DIRECTORY);
    }

    JavaVersion getTargetVersion();

    void forEach(final BiConsumer<CompilerOption, String> consumer);

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
        public Optional<Path> getPathOption(final CompilerOption compilerOptions) {
            return getOption(compilerOptions)
                    .map(this::valueAsPath);
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

    }

}

