package io.github.potjerodekool.nabu.maven;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.log.LoggerFactory;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
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

/**
 * A maven mojo that invokes the Nabu compiler
 */
@Mojo(
        name = "compile",
        defaultPhase = LifecyclePhase.COMPILE,
        requiresDependencyCollection = ResolutionScope.COMPILE,
        threadSafe = true
)
public class NabuMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(readonly = true)
    private Set<String> sourceFileExtensions;

    /**
     * Create new instance.
     */
    public NabuMojo() {
    }

    @Override
    public void execute() throws MojoFailureException {
        getLog().info("Executing NabuMojo");

        configureLogging();

        final var nabuCompiler = new NabuCompiler();

        final var options = configureOptions();

        final var resultCode = nabuCompiler.compile(options);

        if (resultCode != 0) {
            throw new MojoFailureException("Compilation failed with result code " + resultCode);
        }
    }

    private CompilerOptions configureOptions() throws MojoFailureException {
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

    private void configureClassPath(final CompilerOptions.CompilerOptionsBuilder compilerOptionsBuilder) throws MojoFailureException {
        final var paths = new ArrayList<String>();
        paths.add(project.getBuild().getOutputDirectory());

        try {
            paths.addAll(project.getCompileClasspathElements().stream()
                    .map(element -> new File(element).getAbsolutePath())
                    .toList());
        } catch (final DependencyResolutionRequiredException e) {
            throw new MojoFailureException(e);
        }

        /*
        paths.addAll(project.getArtifacts().stream()
                .map(Artifact::getFile)
                .map(File::getAbsolutePath)
                .toList());
        */

        compilerOptionsBuilder.option(
                CompilerOption.CLASS_PATH,
                String.join(File.pathSeparator, paths)
        );
    }

    /*
     * Configures a logger that delegates to maven.
     */
    private void configureLogging() {
        final var mavenLogger = new MavenLogger(getLog());
        LoggerFactory.setProvider((name) -> mavenLogger);
    }
}

