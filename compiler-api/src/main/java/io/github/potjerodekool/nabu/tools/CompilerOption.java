package io.github.potjerodekool.nabu.tools;

import java.util.ArrayList;
import java.util.List;

public record CompilerOption(String optionName) {

    public static final CompilerOption SOURCE_PATH = new CompilerOption("--source-path");
    public static final CompilerOption MODULE_SOURCE_PATH = new CompilerOption("--module-source-path");
    public static final CompilerOption CLASS_PATH = new CompilerOption("--class-path");
    public static final CompilerOption SYSTEM = new CompilerOption(" --system");
    public static final CompilerOption TARGET_VERSION = new CompilerOption("--target");
    public static final CompilerOption SOURCE_OUTPUT = new CompilerOption("-s");
    public static final CompilerOption CLASS_OUTPUT = new CompilerOption("-d");
    public static final CompilerOption ANNOTATION_PROCESSOR_PATH = new CompilerOption("--processor-path");
    public static final CompilerOption ANNOTATION_PROCESSOR_MODULE_PATH = new CompilerOption("--processor-module-path");
    public static final CompilerOption BACKEND = new CompilerOption("--backend");

    public static final List<CompilerOption> COMPILER_OPTIONS = List.of(
            SOURCE_PATH,
            MODULE_SOURCE_PATH,
            CLASS_PATH,
            SYSTEM,
            TARGET_VERSION,
            SOURCE_OUTPUT,
            CLASS_OUTPUT,
            ANNOTATION_PROCESSOR_PATH,
            ANNOTATION_PROCESSOR_MODULE_PATH,
            BACKEND
    );

    @Override
    public String optionName() {
        return optionName;
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof CompilerOption(String name)
                && optionName.equals(name);
    }
}
