    package io.github.potjerodekool.nabu.maven;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.compiler.Options;
import io.github.potjerodekool.nabu.compiler.log.LoggerFactory;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

@Mojo(
        name = "compile",
        defaultPhase = LifecyclePhase.COMPILE,
        requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME
)
public class NabuMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Override
    public void execute() {
        getLog().info("Executing NabuMojo");

        configureLogging();

        try (final var nabuCompiler = new NabuCompiler()) {
            final var options = new Options();

            configureClassPath(nabuCompiler);
            configureSourceRoots(options);
            options.targetDirectory(Paths.get(project.getBuild().getOutputDirectory()));

            try {
                nabuCompiler.compile(options);
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void configureSourceRoots(final Options options) {
        final var sourceRoots = project.getCompileSourceRoots();
        sourceRoots.forEach(sourceRoot -> {
            getLog().info("sourceRoot: " + sourceRoot);
            options.sourceRoot(Paths.get(sourceRoot));
        });
    }

    private void configureClassPath(final NabuCompiler nabuCompiler) {
        nabuCompiler.addClassPathEntry(
                Paths.get(project.getBuild().getOutputDirectory())
        );

        final var artifacts = project.getArtifacts().stream()
                .map(Artifact::getFile)
                .map(File::toPath)
                .toList();
        artifacts.forEach(nabuCompiler::addClassPathEntry);
    }

    private void configureLogging() {
        final var mavenLogger = new MavenLogger(
                getLog()
        );
        LoggerFactory.setProvider((name) -> mavenLogger);
    }
}

