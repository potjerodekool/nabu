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
import java.util.function.Function;

public class PluginRegistry {

    private final Logger logger = Logger.getLogger(PluginRegistry.class.getName());
    private final ExtensionManager extensionManager = new ExtensionManager(this);
    private final LanguageParserManager languageParserManager = new LanguageParserManager(this);
    private final LanguageSupportManager languageSupportManager = new LanguageSupportManager(this);
    private final SharedLoader sharedLoader = new SharedLoader();

    public void registerPlugins(final CompilerContext compilerContext) {
        languageParserManager.init(compilerContext);
        languageSupportManager.init(compilerContext);
        this.extensionManager.init(compilerContext);

        final var loadedPlugins = new HashSet<String>();

        loadPluginsFromUserDirectory(loadedPlugins);
        loadPluginsFromClassPath(loadedPlugins);
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
                stream.filter(it -> it.getFileName().endsWith(".jar"))
                        .forEach(jarFile -> {
                            logger.log(LogLevel.INFO, "Loading plugins from: " + jarFile);

                            try (final var fs = FileSystems.newFileSystem(jarFile, (ClassLoader) null)) {
                                final var pluginPath = fs.getPath("plugin.xml").toUri().toURL();
                                if (registerPlugin(pluginPath, loadedPlugins)) {
                                    final var pluginClassLoader = new PluginClassLoader(pluginPath, getClass().getClassLoader());
                                    sharedLoader.addPluginClassLoader(pluginClassLoader);
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

    private boolean registerPlugin(final URL url,
                                   final Set<String> loadedPlugins) throws ParserConfigurationException, SAXException {
        final var saxParser = SAXParserFactory.newInstance().newSAXParser();
        return registerPlugin(url, saxParser, loadedPlugins);
    }

    private boolean registerPlugin(final URL url,
                                   final SAXParser saxParser,
                                   final Set<String> loadedPlugins) {
        try {
            logger.log(LogLevel.INFO, "Parsing plugin.xml from " + url);
            final var handler = new PluginHandler(this.extensionManager, loadedPlugins);
            saxParser.parse(url.openStream(), handler);
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

    public <T> List<T> createExtensions(final boolean createSingleton,
                                        final String name,
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
                                createSingleton,
                                compilerContext
                        )
                )
                .toList();
    }

    public <T> T createExtension(final PluginExtension extension,
                                 final Class<T> extensionClass,
                                 final boolean createSingleton,
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

            if (createSingleton) {
                extension.setSingleton(instance);
            }
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

