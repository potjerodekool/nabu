package io.github.potjerodekool.nabu.compiler.extension;

import java.net.URL;
import java.net.URLClassLoader;

public class PluginClassLoader extends URLClassLoader {

    public PluginClassLoader( final URL url, final ClassLoader parent) {
        super(new URL[]{url}, parent);
    }
}
