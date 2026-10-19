package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * {@code ResourceBundle.getBundle} in a native executable (its overloads are
 * substituted to call {@link #getBundle}). The JDK's own lookup goes through
 * the module system and class loaders, which a native program does not
 * have. This one follows the same rules over what the executable does have:
 * for each candidate locale (most specific first, as
 * {@link ResourceBundle.Control#getCandidateLocales} gives them) a
 * class-based bundle (registered for reflection) or else a
 * {@code .properties} resource (embedded through the resource
 * configuration). If only the base bundle exists and the locale is not the
 * default, the default locale is tried first, as the JDK does.
 *
 * <p>The bundles found are chained in a {@link ChainBundle}, since the
 * parent of a bundle can only be set from {@code java.util}. Written for
 * bytecode lowering: no string concatenation with {@code +} and no lambdas.
 */
public final class BundleSupport {

    private static final Map<String, ResourceBundle> CACHE = new HashMap<>();

    private BundleSupport() {
    }

    public static ResourceBundle getBundle(final String baseName, final Locale locale,
                                           final ResourceBundle.Control control) {
        if (baseName == null || locale == null) {
            throw new NullPointerException();
        }
        final String key = baseName.concat("\u0000").concat(locale.toLanguageTag());
        synchronized (CACHE) {
            final ResourceBundle cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        final ResourceBundle.Control actual = control != null
                ? control
                : ResourceBundle.Control.getControl(ResourceBundle.Control.FORMAT_DEFAULT);
        List<ResourceBundle> found = load(baseName, locale, actual);
        final Locale defaultLocale = Locale.getDefault();
        if (!locale.equals(defaultLocale) && onlyBase(found)) {
            final List<ResourceBundle> fallback = load(baseName, defaultLocale, actual);
            if (!fallback.isEmpty()) {
                found = fallback;
            }
        }
        if (found.isEmpty()) {
            throw new MissingResourceException(
                    new StringBuilder("Can't find bundle for base name ").append(baseName)
                            .append(", locale ").append(locale).toString(),
                    new StringBuilder(baseName).append('_').append(locale).toString(), "");
        }
        final ResourceBundle bundle = found.size() == 1 ? found.get(0) : new ChainBundle(found);
        synchronized (CACHE) {
            CACHE.put(key, bundle);
        }
        return bundle;
    }

    private static boolean onlyBase(final List<ResourceBundle> found) {
        return found.isEmpty() || (found.size() == 1 && Locale.ROOT.equals(found.get(0).getLocale()));
    }

    private static List<ResourceBundle> load(final String baseName, final Locale locale,
                                             final ResourceBundle.Control control) {
        final List<ResourceBundle> found = new ArrayList<>();
        for (final Locale candidate : control.getCandidateLocales(baseName, locale)) {
            final ResourceBundle bundle = loadOne(control.toBundleName(baseName, candidate), candidate);
            if (bundle != null) {
                found.add(bundle);
            }
        }
        return found;
    }

    private static ResourceBundle loadOne(final String bundleName, final Locale locale) {
        try {
            // Class.newInstance: the constructor table of the runtime, which
            // has the classes registered for reflection.
            @SuppressWarnings("deprecation")
            final Object instance = Class.forName(bundleName).newInstance();
            if (instance instanceof ResourceBundle bundle) {
                return new ChainBundle(Collections.singletonList(bundle), locale);
            }
        } catch (final Exception | LinkageError e) {
            // No class-based bundle: try the properties.
        }
        final InputStream in = ResourceSupport.open(bundleName.replace('.', '/').concat(".properties"));
        if (in == null) {
            return null;
        }
        try {
            try {
                return new ChainBundle(Collections.singletonList(new PropertyResourceBundle(in)), locale);
            } finally {
                in.close();
            }
        } catch (final java.io.IOException e) {
            return null;
        }
    }

    /**
     * Bundles searched in order (most specific first); {@link #getLocale()}
     * is the locale of the first.
     */
    static final class ChainBundle extends ResourceBundle {

        private final List<ResourceBundle> bundles;
        private final Locale bundleLocale;

        ChainBundle(final List<ResourceBundle> bundles) {
            this(bundles, bundles.get(0).getLocale());
        }

        ChainBundle(final List<ResourceBundle> bundles, final Locale bundleLocale) {
            this.bundles = bundles;
            this.bundleLocale = bundleLocale;
        }

        @Override
        public Locale getLocale() {
            return bundleLocale;
        }

        @Override
        protected Object handleGetObject(final String key) {
            for (final ResourceBundle bundle : bundles) {
                if (bundle.containsKey(key)) {
                    return bundle.getObject(key);
                }
            }
            return null;
        }

        @Override
        public Enumeration<String> getKeys() {
            final Set<String> keys = new LinkedHashSet<>();
            for (final ResourceBundle bundle : bundles) {
                keys.addAll(bundle.keySet());
            }
            return Collections.enumeration(keys);
        }
    }
}
