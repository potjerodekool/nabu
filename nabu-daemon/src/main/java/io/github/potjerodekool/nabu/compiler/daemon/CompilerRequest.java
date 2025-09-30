package io.github.potjerodekool.nabu.compiler.daemon;

import java.util.List;

public record CompilerRequest(String outputDirectory,
                              List<String> classPath,
                              List<String> sourcePath) {
}
