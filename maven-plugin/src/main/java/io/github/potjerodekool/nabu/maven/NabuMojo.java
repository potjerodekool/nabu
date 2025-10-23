package io.github.potjerodekool.nabu.maven;

import io.github.potjerodekool.nabu.compiler.impl.NabuCompiler;
import io.github.potjerodekool.nabu.compiler.log.LoggerFactory;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

@Mojo(
        name = "compile",
        defaultPhase = LifecyclePhase.COMPILE,
        requiresDependencyCollection = ResolutionScope.COMPILE
)
public class NabuMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(readonly = true)
    private Set<String> sourceFileExtensions;

    @Override
    public void execute() throws MojoFailureException {
        getLog().info("Executing NabuMojo");

        configureLogging();

        final var nabuCompiler = new NabuCompiler();

        final var options = configureOptions();

        final var resultCode =nabuCompiler.compile(options);

        if (resultCode != 0) {
            throw new MojoFailureException("Compilation failed with result code " + resultCode);
        }
    }

    private CompilerOptions configureOptions() {
        final var compilerOptionsBuilder = new CompilerOptions.CompilerOptionsBuilder();

        configureClassPath(compilerOptionsBuilder);
        configureSourceRoots(compilerOptionsBuilder);

        compilerOptionsBuilder.option(
                CompilerOption.TARGET_DIRECTORY,
                project.getBuild().getOutputDirectory()
        );

        if (sourceFileExtensions != null && !sourceFileExtensions.isEmpty()) {
            compilerOptionsBuilder.option(
                    CompilerOption.SOURCE_FILE_EXTENSIONS,
                    String.join(",", sourceFileExtensions)
            );
        }

        return compilerOptionsBuilder.build();
    }

    private void configureSourceRoots(final CompilerOptions.CompilerOptionsBuilder compilerOptionsBuilder) {
        final var sourceRoots = project.getCompileSourceRoots();
        final var sourcePath = sourceRoots.stream()
                .peek(sourceRoot -> getLog().info("sourceRoot: " + sourceRoot))
                .collect(Collectors.joining(File.pathSeparator));
        compilerOptionsBuilder.option(CompilerOption.SOURCE_PATH, sourcePath);
    }

    private void configureClassPath(final CompilerOptions.CompilerOptionsBuilder compilerOptionsBuilder) {
        final var paths = new ArrayList<String>();
        paths.add(project.getBuild().getOutputDirectory());

        paths.addAll(project.getArtifacts().stream()
                .map(Artifact::getFile)
                .map(File::getAbsolutePath)
                .toList());

        compilerOptionsBuilder.option(
                CompilerOption.CLASS_PATH,
                String.join(File.pathSeparator, paths)
        );
    }

    private void configureLogging() {
        final var mavenLogger = new MavenLogger(
                getLog()
        );
        LoggerFactory.setProvider((name) -> mavenLogger);
    }
}

