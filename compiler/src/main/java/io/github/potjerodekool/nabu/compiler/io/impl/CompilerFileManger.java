package io.github.potjerodekool.nabu.compiler.io.impl;

import io.github.potjerodekool.nabu.compiler.extension.PluginRegistry;
import io.github.potjerodekool.nabu.tools.FileManager;

public interface CompilerFileManger extends FileManager {
    void initialize(PluginRegistry pluginRegistry);
}
