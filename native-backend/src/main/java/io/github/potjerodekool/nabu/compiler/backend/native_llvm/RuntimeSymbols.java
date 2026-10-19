package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * De JDK-symbolen ({@code pkg.Owner_member}) die de C-runtime zelf levert,
 * afgelezen uit de gebundelde runtime-bronnen. Elke string-literal met de
 * vorm van zo'n symbool telt mee: te ruim is hier veilig (een symbool blijft
 * dan extern en een ontbrekende definitie komt bij het linken boven water),
 * te krap zou een stub naast de C-definitie zetten.
 */
final class RuntimeSymbols {

    private static final Pattern SYMBOL =
            Pattern.compile("\"([A-Za-z_\\[][\\w.$\\[;]*\\.[\\w.$\\[;]*_[\\w$<>]+)\"");

    /**
     * {@code JCL_FS("name")} in jcl_lite.c: a file-system native, bound as
     * {@code java.io.WinNTFileSystem_name} or {@code java.io.UnixFileSystem_name}
     * depending on the platform.
     */
    private static final Pattern FILE_SYSTEM_SYMBOL = Pattern.compile("JCL_FS\\(\"([\\w$]+)\"\\)");

    private static final String[] SOURCES = {"jcl_lite.c", "nabu_runtime.c"};

    private RuntimeSymbols() {
    }

    static Set<String> load() {
        final Set<String> symbols = new HashSet<>();
        final ClassLoader loader = RuntimeSymbols.class.getClassLoader();
        if (loader == null) {
            return symbols;
        }
        for (final String source : SOURCES) {
            try (var in = loader.getResourceAsStream("nabu/runtime/" + source)) {
                if (in == null) {
                    continue;
                }
                final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                final Matcher matcher = SYMBOL.matcher(text);
                while (matcher.find()) {
                    symbols.add(matcher.group(1));
                }
                final Matcher fileSystem = FILE_SYSTEM_SYMBOL.matcher(text);
                while (fileSystem.find()) {
                    symbols.add("java.io.WinNTFileSystem_" + fileSystem.group(1));
                    symbols.add("java.io.UnixFileSystem_" + fileSystem.group(1));
                }
            } catch (final IOException e) {
                // Zonder de bron is er niets af te lezen; de linker meldt
                // dan zelf wat er dubbel of afwezig is.
            }
        }
        return symbols;
    }
}
