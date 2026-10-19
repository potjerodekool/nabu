package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

import io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.BundleSupport;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * The public {@code ResourceBundle.getBundle} overloads, see
 * {@link JavaSubstitutions}: the JDK looks bundles up through the module
 * system and class loaders, which a native executable does not have;
 * {@link BundleSupport} looks them up among the reflection-registered
 * classes and embedded resources. A module or class loader argument does
 * not matter with one class loader.
 */
public final class ResourceBundleSubstitution {

    private ResourceBundleSubstitution() {
    }

    public static ResourceBundle getBundle(final String baseName) {
        return BundleSupport.getBundle(baseName, Locale.getDefault(), null);
    }

    public static ResourceBundle getBundle(final String baseName, final ResourceBundle.Control control) {
        return BundleSupport.getBundle(baseName, Locale.getDefault(), control);
    }

    public static ResourceBundle getBundle(final String baseName, final Locale locale) {
        return BundleSupport.getBundle(baseName, locale, null);
    }

    public static ResourceBundle getBundle(final String baseName, final Module module) {
        return BundleSupport.getBundle(baseName, Locale.getDefault(), null);
    }

    public static ResourceBundle getBundle(final String baseName, final Locale locale, final Module module) {
        return BundleSupport.getBundle(baseName, locale, null);
    }

    public static ResourceBundle getBundle(final String baseName, final Locale locale,
                                           final ResourceBundle.Control control) {
        return BundleSupport.getBundle(baseName, locale, control);
    }

    public static ResourceBundle getBundle(final String baseName, final Locale locale, final ClassLoader loader) {
        return BundleSupport.getBundle(baseName, locale, null);
    }

    public static ResourceBundle getBundle(final String baseName, final Locale locale, final ClassLoader loader,
                                           final ResourceBundle.Control control) {
        return BundleSupport.getBundle(baseName, locale, control);
    }
}
