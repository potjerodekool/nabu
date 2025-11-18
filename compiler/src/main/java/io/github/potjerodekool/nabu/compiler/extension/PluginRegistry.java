package io.github.potjerodekool.nabu.compiler.extension;

import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;

public class PluginRegistry {

    private final Logger logger = Logger.getLogger(PluginRegistry.class.getName());
    private final ExtensionManager extensionManager = new ExtensionManager(this);

    public void registerPlugins(final CompilerContext compilerContext) {
        this.extensionManager.init(compilerContext);

        try {
            final var resources = getClass().getClassLoader().getResources("plugin.xml");
            final var saxParser = SAXParserFactory.newInstance().newSAXParser();
            resources.asIterator().forEachRemaining(url -> registerPlugin(url, saxParser));

        } catch (IOException | ParserConfigurationException | SAXException e) {
            throw new RuntimeException(e);
        }
    }

    private void registerPlugin(final URL url,
                                final SAXParser saxParser) {
        try {
            logger.log(LogLevel.INFO, "Parsing plugin.xml from " + url);
            saxParser.parse(url.openStream(), new PluginHandler(this.extensionManager));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse plugin.xml", e);
        }
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
                .map(extension -> extension.createExtension(
                        extensionClass,
                        createSingleton,
                        compilerContext
                ))
                .toList();
    }

    public ExtensionManager getExtensionManager() {
        return extensionManager;
    }

    private static class PluginHandler extends DefaultHandler {

        private static final String EXTENSIONS_ELEMENT = "extensions";
        private final ExtensionManager extensionManager;
        private boolean inExtensionsElement = false;

        public PluginHandler(final ExtensionManager extensionManager) {
            this.extensionManager = extensionManager;
        }

        @Override
        public void startElement(final String uri,
                                 final String localName,
                                 final String qName,
                                 final Attributes attributes) {
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
        public void endElement(final String uri, final String localName, final String qName) {
            if (EXTENSIONS_ELEMENT.equals(qName)) {
                inExtensionsElement = false;
            }
        }
    }

}

