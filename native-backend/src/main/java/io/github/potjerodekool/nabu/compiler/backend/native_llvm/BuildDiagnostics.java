package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects the warnings of one build by category, so the build prints a
 * single summary line per category and the details go to a file. The
 * emitters report here instead of printing one line per occurrence, which
 * buried real problems.
 */
final class BuildDiagnostics {

    /** The categories, in the order they are reported. */
    enum Category {
        STATIC_FIELD_OUTSIDE_CLOSURE("static fields of classes outside the compiled closure keep their default value (0/null)"),
        UNDECLARED_CALL("calls to methods with no declaration for their parameters, declared as external symbols"),
        INDIRECT_CALL_SIGNATURE("indirect calls whose signature was derived from the operands"),
        INACCESSIBLE_FIELD("fields that are not accessible and yield an undefined value");

        private final String description;

        Category(final String description) {
            this.description = description;
        }
    }

    private static final Map<Category, Set<String>> WARNINGS = new LinkedHashMap<>();

    private BuildDiagnostics() {
    }

    /** Forgets the warnings of an earlier build. */
    static synchronized void reset() {
        WARNINGS.clear();
    }

    static synchronized void warn(final Category category, final String detail) {
        WARNINGS.computeIfAbsent(category, k -> new LinkedHashSet<>()).add(detail);
    }

    /** The warnings of a category, for tests. */
    static synchronized List<String> warnings(final Category category) {
        return new ArrayList<>(WARNINGS.getOrDefault(category, Set.of()));
    }

    /**
     * Prints one line per category and writes every warning to {@code file}.
     * Prints nothing when there are no warnings.
     */
    static synchronized void report(final Path file) {
        if (WARNINGS.isEmpty()) {
            return;
        }
        final List<String> lines = new ArrayList<>();
        for (final Category category : Category.values()) {
            final Set<String> details = WARNINGS.get(category);
            if (details == null) {
                continue;
            }
            System.err.println("[nabu/LLVM] warning: " + details.size() + " "
                    + category.description + ".");
            lines.add("# " + details.size() + " " + category.description);
            lines.addAll(details);
            lines.add("");
        }
        try {
            Files.write(file, lines);
            System.err.println("[nabu/LLVM] warnings are listed in " + file);
        } catch (final IOException e) {
            System.err.println("[nabu/LLVM] could not write " + file + ": " + e.getMessage());
        }
    }
}
