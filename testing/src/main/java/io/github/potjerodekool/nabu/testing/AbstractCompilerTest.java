package io.github.potjerodekool.nabu.testing;

import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.tools.*;
import org.junit.jupiter.api.BeforeEach;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ServiceLoader;

public abstract class AbstractCompilerTest {

    private CompilerContext compilerContext;

    public CompilerContext getCompilerContext() {
        return compilerContext;
    }

    protected ModuleElement getUnnamedModule() {
        return compilerContext.getModules().getUnnamedModule();
    }

    private File resolveCompilerDirectory() {
        var current = new File(".").getAbsoluteFile();

        while (!current.isDirectory() || !"nabu".equals(current.getName())) {
            current = current.getParentFile();
        }

        return new File(current, "compiler");
    }

    protected String getClassPath() {
        return null;
    }

    @BeforeEach
    public void setupCompiler() {
        final var rootDirectory = resolveCompilerDirectory();
        final var rootPath = rootDirectory.getAbsolutePath();

        final var optionBuilder = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, rootPath + "/src/test/resources/classes")
                .option(CompilerOption.MODULE_SOURCE_PATH, rootPath + "/src/test/resources/jmods");

        final var classPath = getClassPath();

        if (classPath != null && !classPath.isEmpty()) {
            optionBuilder.option(CompilerOption.CLASS_PATH, classPath);
        }

        final var options = optionBuilder.build();
        final var compiler = createCompiler();
        this.compilerContext = compiler.configure(options);
    }

    private Compiler createCompiler() {
        return ServiceLoader.load(Compiler.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("No compiler implementation found"));
    }

    protected String loadResource(final String resourceName) {
        try (var resource = resolveResource(resourceName)) {
            return new String(resource.readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private InputStream resolveResource(final String resourceName) throws IOException {
        final var input = getClass().getClassLoader().getResourceAsStream(resourceName);

        if (input != null) {
            return input;
        } else {
            final var absolute = Paths.get("src/test/resources/" + resourceName).toAbsolutePath();
            return Files.newInputStream(absolute);
        }
    }

}
