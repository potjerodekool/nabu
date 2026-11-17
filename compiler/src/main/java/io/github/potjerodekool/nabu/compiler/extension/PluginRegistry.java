package io.github.potjerodekool.nabu.compiler.extension;

import io.github.potjerodekool.nabu.lang.spi.SourceParser;
import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.FileObject;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class PluginRegistry {

    private final Logger logger = Logger.getLogger(PluginRegistry.class.getName());
    private final ExtensionManager extensionManager = new ExtensionManager(this);
    private final LanguageParserManager languageParserManager = new LanguageParserManager(this);
    private final LanguageSupportManager languageSupportManager = new LanguageSupportManager(this);
    private final SharedLoader sharedLoader = new SharedLoader();

    /**
     * The class loaders of the plugin jars, kept for the life of the JVM. The generated ANTLR parsers of a plugin
     * (for example the Nabu language) keep their DFA in a static field, built lazily and slowly: a new class loader per
     * compilation made every compilation in a long living JVM (mvnd, the daemon, an IDE) start with a cold parser.
     * A jar that changes (path, modification time or size) gets a new loader.
     */
    private static final Map<PluginLoaderKey, PluginClassLoader> PLUGIN_CLASS_LOADERS = new java.util.concurrent.ConcurrentHashMap<>();

    private record PluginLoaderKey(java.nio.file.Path jarFile,
                                   long lastModified,
                                   long size,
                                   ClassLoader parent) {
    }

    private PluginClassLoader pluginClassLoader(final java.nio.file.Path jarFile) throws IOException {
        final var key = new PluginLoaderKey(
                jarFile.toAbsolutePath().normalize(),
                Files.getLastModifiedTime(jarFile).toMillis(),
                Files.size(jarFile),
                getClass().getClassLoader()
        );
        final var existing = PLUGIN_CLASS_LOADERS.get(key);
        if (existing != null) {
            return existing;
        }
        URL loadFrom;
        try {
            loadFrom = shadowCopy(key).toUri().toURL();
        } catch (final IOException e) {
            logger.log(LogLevel.WARN, "Could not copy " + jarFile + " (" + e + "); loading it in place");
            loadFrom = jarFile.toUri().toURL();
        }
        final URL root = loadFrom;
        return PLUGIN_CLASS_LOADERS.computeIfAbsent(key, k -> new PluginClassLoader(root, k.parent()));
    }

    /**
     * The loader reads a copy of the jar, not the jar itself: a loader kept for the life of the JVM keeps its jar open,
     * and on Windows an open jar cannot be replaced (a build that installs a new native-backend.jar into
     * ~/.nabu/plugins failed while mvnd or the daemon had compiled with the old one). The copy is named after the
     * modification time and size of the jar, so a JVM that sees the same jar again reuses it; older copies of the same
     * jar are removed when they are no longer open.
     */
    private static java.nio.file.Path shadowCopy(final PluginLoaderKey key) throws IOException {
        final var directory = Paths.get(System.getProperty("java.io.tmpdir"), "nabu-plugins");
        Files.createDirectories(directory);
        final var name = key.jarFile().getFileName().toString();
        final var base = name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
        final var copy = directory.resolve(base + "-" + key.lastModified() + "-" + key.size() + ".jar");
        if (!Files.exists(copy) || Files.size(copy) != key.size()) {
            final var partial = Files.createTempFile(directory, base, ".part");
            try {
                Files.copy(key.jarFile(), partial, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.move(partial, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (final IOException e) {
                Files.deleteIfExists(partial);
                if (!Files.exists(copy) || Files.size(copy) != key.size()) {
                    throw e;
                }
                // another JVM made the same copy at the same time (and has it open): use that one
            }
        }
        try (var others = Files.list(directory)) {
            others.filter(other -> !other.equals(copy))
                    .filter(other -> other.getFileName().toString().matches(
                            java.util.regex.Pattern.quote(base) + "-\\d+-\\d+\\.jar"))
                    .forEach(other -> {
                        try {
                            Files.deleteIfExists(other);
                        } catch (final IOException ignored) {
                            // still open in some JVM
                        }
                    });
        }
        return copy;
    }

    public void registerPlugins(final CompilerContext compilerContext) {
        languageParserManager.init(compilerContext);
        languageSupportManager.init(compilerContext);
        this.extensionManager.init(compilerContext);

        final var loadedPlugins = new HashSet<String>();

        loadPluginsFromClassPath(loadedPlugins);
        loadPluginsFromUserDirectory(loadedPlugins);
    }

    private void loadPluginsFromUserDirectory(final HashSet<String> loadedPlugins) {
        final var userDir = System.getProperty("user.home");

        if (userDir != null) {
            final var pluginsDirectory = Paths.get(userDir, ".nabu", "plugins");

            if (!Files.exists(pluginsDirectory)) {
                try {
                    Files.createDirectories(pluginsDirectory);
                } catch (final IOException ignored) {
                }
            }

            if (!Files.exists(pluginsDirectory)) {
                return;
            }

            try (var stream = Files.list(pluginsDirectory)) {
                stream.filter(it -> it.getFileName().toString().endsWith(".jar"))
                        .forEach(jarFile -> {
                            logger.log(LogLevel.INFO, "Loading plugins from: " + jarFile);

                            try (final var fs = FileSystems.newFileSystem(jarFile, (ClassLoader) null)) {
                                // Read through the zip file system, not a jar: URL: the JDK caches the JarFile behind
                                // a jar: URL connection and never closes it, which kept the plugin jar locked on Windows.
                                final boolean registered;
                                try (var in = Files.newInputStream(fs.getPath("plugin.xml"))) {
                                    registered = registerPlugin(in, jarFile.toString(), loadedPlugins);
                                }
                                if (registered) {
                                    sharedLoader.addPluginClassLoader(pluginClassLoader(jarFile));
                                }
                            } catch (Exception ignored) {
                            }
                        });
/*
                for (final var path : stream) {
                    //PluginClassLoader

                }
                */
            } catch (IOException ignored) {
                logger.log(LogLevel.ERROR, "Failed to load plugins from user directory");
            }
        }
    }

    private void loadPluginsFromClassPath(final Set<String> loadedPlugins) {
        try {
            final var resources = getClass().getClassLoader().getResources("plugin.xml");
            final var saxParser = SAXParserFactory.newInstance().newSAXParser();
            resources.asIterator().forEachRemaining(url -> registerPlugin(url, saxParser, loadedPlugins));
        } catch (IOException | ParserConfigurationException | SAXException ignored) {
            logger.log(LogLevel.ERROR, "Failed to load plugins from classpath");
        }
    }

    private boolean registerPlugin(final java.io.InputStream in,
                                   final String source,
                                   final Set<String> loadedPlugins) throws ParserConfigurationException, SAXException {
        return registerPlugin(in, source, SAXParserFactory.newInstance().newSAXParser(), loadedPlugins);
    }

    private boolean registerPlugin(final URL url,
                                   final SAXParser saxParser,
                                   final Set<String> loadedPlugins) {
        try (var in = url.openStream()) {
            return registerPlugin(in, url.toString(), saxParser, loadedPlugins);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse plugin.xml", e);
        }
    }

    private boolean registerPlugin(final java.io.InputStream in,
                                   final String source,
                                   final SAXParser saxParser,
                                   final Set<String> loadedPlugins) {
        try {
            logger.log(LogLevel.INFO, "Parsing plugin.xml from " + source);
            final var handler = new PluginHandler(this.extensionManager, loadedPlugins);
            saxParser.parse(in, handler);
            if (!handler.skippedPlugin()) {
                loadedPlugins.add(handler.getPluginId());
                return true;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse plugin.xml", e);
        }
        return false;
    }

    public List<PluginExtension> getExtensions(final String name) {
        return this.extensionManager.getExtensions(name);
    }

    public <T> List<T> createExtensions(final String name,
                                        final Class<T> extensionClass,
                                        final CompilerContext compilerContext) {
        final var extensions = this.extensionManager.getExtensions(name);
        if (extensions.isEmpty()) {
            return List.of();
        }

        return extensions.stream()
                .map(extension ->
                        createExtension(
                                extension,
                                extensionClass,
                                compilerContext
                        )
                )
                .toList();
    }

    public <T> T createExtension(final PluginExtension extension,
                                 final Class<T> extensionClass,
                                 final CompilerContext compilerContext) {
        final var singleton = extension.getSingleton();

        if (singleton != null) {
            return (T) singleton;
        }

        final var implementationClass = extension.getImplementationClass();
        if (implementationClass == null) {
            throw new IllegalArgumentException("No implementation class specified for extension " + extensionClass.getName());
        }
        try {
            final var clazz = sharedLoader.loadClass(implementationClass);
            if (!extensionClass.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException("Implementation class " + implementationClass + " is not assignable to " + extensionClass.getName());
            }

            final T instance;
            final var constructors = clazz.getDeclaredConstructors();

            if (constructors.length == 0) {
                throw new IllegalArgumentException("Implementation class " + implementationClass + " has no declared constructors");
            } else {
                final var constructor = constructors[0];
                if (constructor.getParameterCount() == 0) {
                    instance = (T) constructor.newInstance();
                } else {
                    instance = (T) constructor.newInstance(compilerContext);
                }
            }

            extension.setSingleton(instance);
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create extension " + extensionClass.getName() + " with implementation class " + implementationClass, e);
        }
    }

    public Collection<FileObject.Kind> getSourceKindsForCompilation() {
        return languageParserManager.getSourceKinds();
    }

    public ExtensionManager getExtensionManager() {
        return extensionManager;
    }

    public Optional<? extends SourceParser> getSourceParser(final FileObject.Kind kind) {
        var sourceParserOptional = getLanguageSupportManager().getLanguageSupporter(kind);

        if (sourceParserOptional.isPresent()) {
            return sourceParserOptional;
        } else {
            return getLanguageParserManager().getLanguageParser(kind);
        }
    }

    public LanguageParserManager getLanguageParserManager() {
        return languageParserManager;
    }

    public LanguageSupportManager getLanguageSupportManager() {
        return languageSupportManager;
    }

    private static class PluginHandler extends DefaultHandler {

        private static final String ID = "id";
        private static final String EXTENSIONS_ELEMENT = "extensions";
        private final ExtensionManager extensionManager;
        private final Set<String> loadedPlugins;
        private boolean inExtensionsElement = false;
        private String pluginId;
        private boolean skipPlugin = false;

        public PluginHandler(final ExtensionManager extensionManager,
                             final Set<String> loadedPlugins) {
            this.extensionManager = extensionManager;
            this.loadedPlugins = loadedPlugins;
        }

        public String getPluginId() {
            return pluginId;
        }

        public boolean skippedPlugin() {
            return skipPlugin;
        }

        @Override
        public void startElement(final String uri,
                                 final String localName,
                                 final String qName,
                                 final Attributes attributes) {
            if (skipPlugin) {
                return;
            }

            if (EXTENSIONS_ELEMENT.equals(qName)) {
                inExtensionsElement = true;
            } else if (inExtensionsElement) {
                final var attributeCount = attributes.getLength();
                final var attributesMap = new HashMap<String, String>(attributeCount);

                for (int i = 0; i < attributeCount; i++) {
                    final var attributeName = attributes.getLocalName(i);
                    final var attributeValue = attributes.getValue(i);
                    attributesMap.put(attributeName, attributeValue);
                }

                this.extensionManager.registerExtension(qName, new PluginExtension(attributesMap));
            }
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) throws SAXException {
            this.pluginId += new String(ch, start, length);
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            if (EXTENSIONS_ELEMENT.equals(qName)) {
                inExtensionsElement = false;
            } else if (ID.equals(qName)) {
                skipPlugin = loadedPlugins.contains(this.pluginId);
            }
        }
    }

}

