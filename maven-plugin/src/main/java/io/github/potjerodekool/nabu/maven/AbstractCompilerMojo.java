package io.github.potjerodekool.nabu.maven;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.log.LoggerFactory;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.model.Dependency;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.util.artifact.JavaScopes;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public abstract class AbstractCompilerMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> remoteProjectRepositories;

    @Parameter(defaultValue = "${project.dependencyManagement}", readonly = true)
    private DependencyManagement dependencyManagement;

    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(property = "maven.compiler.target", defaultValue = "1.8")
    protected String targetVersion;

    @Parameter(property = "maven.compiler.verbose", defaultValue = "false")
    protected boolean verbose;

    @Parameter(property = "maven.compiler.showWarnings", defaultValue = "true")
    protected boolean showWarnings;

    @Parameter(property = "maven.compiler.showDebugs", defaultValue = "true")
    protected boolean showDebugs;

    @Parameter(property = "maven.compiler.failOnError", defaultValue = "true")
    protected boolean failOnError;

    @Parameter
    private List<AnnotationProcessorDependency> annotationProcessorPaths;

    @Component
    private ArtifactHandlerManager artifactHandlerManager;

    @Parameter
    protected Map<String, Object> properties;

    @Parameter
    protected List<String> compilerArgs;

    /**
     * Subclasses must provide the source directories to compile
     */
    protected abstract List<String> getSourceRoots();

    /**
     * Subclasses must provide the output directory
     */
    protected abstract File getOutputDirectory();

    /**
     * Subclasses must provide the classpath elements
     */
    protected abstract List<String> getClasspathElements() throws MojoExecutionException;

    public abstract String getGeneratedSourcesDirectory();

    /**
     * Returns a descriptive name for logging (e.g., "main" or "test")
     */
    protected abstract String getCompilationType();

    private List<File> resolveSourceRoots() {
        return getSourceRoots().stream()
                .map(File::new)
                .filter(File::exists)
                .filter(File::isDirectory)
                .toList();
    }

    @Override
    public void execute() throws MojoExecutionException {
        final var existingSourceDirs = resolveSourceRoots();

        if (existingSourceDirs.isEmpty()) {
            getLog().info("No " + getCompilationType() + " sources to compile");
            return;
        }

        getLog().info("Compiling " + getCompilationType() + " sources");
        if (verbose) {
            getLog().info("Source roots: " + existingSourceDirs);
            getLog().info("Target version: " + targetVersion);
        }

        configureLogging();

        try {
            final var nabuCompiler = new NabuCompiler();
            final var compilerOptions = configureOptions(existingSourceDirs);
            final var resultCode = nabuCompiler.compile(compilerOptions);

            if (resultCode != 0 && failOnError) {
                throw new MojoExecutionException("Compilation failure");
            }

        } catch (final Exception e) {
            throw new MojoExecutionException("Failed to compile " + getCompilationType() + " sources", e);
        }
    }

    private CompilerOptions configureOptions(final List<File> existingSourceDirs) throws MojoExecutionException {
        final var compilerOptionsBuilder = new CompilerOptions.CompilerOptionsBuilder();

        final var outputDirectory = getOutputDirectory().getAbsolutePath();

        configureClassPath(compilerOptionsBuilder);
        configureSourceRoots(existingSourceDirs, compilerOptionsBuilder);
        configureAnnotationProcessorsPaths(compilerOptionsBuilder);
        configureCompilerArguments(compilerOptionsBuilder);

        final var sourceOutput = getGeneratedSourcesDirectory() != null
                ? getGeneratedSourcesDirectory()
                : outputDirectory;

        compilerOptionsBuilder.option(
                CompilerOption.SOURCE_OUTPUT,
                sourceOutput
        );

        compilerOptionsBuilder.option(
                CompilerOption.CLASS_OUTPUT,
                outputDirectory
        );

        if (targetVersion != null) {
            compilerOptionsBuilder.option(
                    CompilerOption.TARGET_VERSION,
                    targetVersion
            );
        }

        return compilerOptionsBuilder.build();
    }

    private String asOptionValue(final Object value) {
        if (value == null) {
            return null;
        } else {
            return value.toString();
        }
    }

    private void configureClassPath(final CompilerOptions.CompilerOptionsBuilder compilerOptionsBuilder)
            throws MojoExecutionException {

        final var classpathFiles = getClasspathElements().stream()
                .map(File::new)
                .filter(File::exists)
                .map(File::getAbsolutePath)
                .toList();

        compilerOptionsBuilder.option(
                CompilerOption.CLASS_PATH,
                joinPath(classpathFiles)
        );
    }

    private String joinPath(final List<String> paths) {
        return String.join(File.pathSeparator, paths);
    }

    private void configureSourceRoots(final List<File> existingSourceDirs,
                                      final CompilerOptions.CompilerOptionsBuilder compilerOptionsBuilder) {
        final var sourceRoots = existingSourceDirs.stream()
                .map(File::getAbsolutePath)
                .toList();

        final var sourcePath = joinPath(sourceRoots);
        compilerOptionsBuilder.option(CompilerOption.SOURCE_PATH, sourcePath);
    }

    private void configureAnnotationProcessorsPaths(final CompilerOptions.CompilerOptionsBuilder compilerOptionsBuilder) throws MojoExecutionException {
        final var paths = resolveAnnotationProcessorPaths();

        if (paths != null && !paths.isEmpty()) {
            compilerOptionsBuilder.option(
                    CompilerOption.ANNOTATION_PROCESSOR_PATH,
                    joinPath(paths)
            );
        }
    }

    private void configureCompilerArguments(final CompilerOptions.CompilerOptionsBuilder compilerOptionsBuilder) {
        if (compilerArgs == null) {
            return;
        }

        compilerArgs.forEach(compilerArg -> {
           var name = compilerArg;
           var value = "true";
           var index = name.indexOf(":");

           if (index > 0) {
               value = name.substring(index + 1);
               name = name.substring(0, index);
           } else {
               index = name.indexOf('=');

               if (index > 0) {
                   value = name.substring(index + 1);
                   name = name.substring(0, index);
               }
           }

           compilerOptionsBuilder.option(new CompilerOption(name), value);
        });

    }

    /*
     * Configures a logger that delegates to maven.
     */
    private void configureLogging() {
        final var mavenLogger = new MavenLogger(getLog(), showWarnings, showDebugs);
        LoggerFactory.setProvider((name) -> mavenLogger);
    }

    private List<String> resolveAnnotationProcessorPaths() throws MojoExecutionException {
        if (annotationProcessorPaths == null || annotationProcessorPaths.isEmpty()) {
            return null;
        } else {
            try {
                final var dependencies = convertToDependencies(annotationProcessorPaths);
                final var collectRequest = new CollectRequest(dependencies, Collections.emptyList(), remoteProjectRepositories);
                final var dependencyRequest = new DependencyRequest();
                dependencyRequest.setCollectRequest(collectRequest);
                final var dependencyResult = repositorySystem.resolveDependencies(session.getRepositorySession(), dependencyRequest);

                return dependencyResult.getArtifactResults().stream()
                        .map(resolved -> resolved.getArtifact().getFile().getAbsolutePath())
                        .toList();
            } catch (final Exception e) {
                throw new MojoExecutionException(
                        "Resolution of annotationProcessorPath dependencies failed: " + e.getLocalizedMessage(), e);
            }
        }
    }

    private List<org.eclipse.aether.graph.Dependency> convertToDependencies(
            final List<AnnotationProcessorDependency> annotationProcessorPaths) throws MojoExecutionException {
        final var dependencies = new ArrayList<org.eclipse.aether.graph.Dependency>();

        for (final AnnotationProcessorDependency annotationProcessorPath : annotationProcessorPaths) {
            final var handler = artifactHandlerManager.getArtifactHandler(annotationProcessorPath.getType());
            final var version = getAnnotationProcessorPathVersion(annotationProcessorPath);
            final var artifact = new DefaultArtifact(
                    annotationProcessorPath.getGroupId(),
                    annotationProcessorPath.getArtifactId(),
                    annotationProcessorPath.getClassifier(),
                    handler.getExtension(),
                    version);
            final var exclusions = convertToAetherExclusions(annotationProcessorPath.getExclusions());
            dependencies.add(new org.eclipse.aether.graph.Dependency(artifact, JavaScopes.RUNTIME, false, exclusions));
        }
        return dependencies;
    }

    private Set<Exclusion> convertToAetherExclusions(final Set<DependencyExclusion> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) {
            return Collections.emptySet();
        }

        return exclusions.stream()
                .map(exclusion -> new Exclusion(
                        exclusion.getGroupId(),
                        exclusion.getArtifactId(),
                        exclusion.getClassifier(),
                        exclusion.getExtension())
                )
                .collect(Collectors.toSet());
    }

    private String getAnnotationProcessorPathVersion(final AnnotationProcessorDependency annotationProcessorPath) throws MojoExecutionException {
        final var configuredVersion = annotationProcessorPath.getVersion();

        if (configuredVersion != null) {
            return configuredVersion;
        } else {
            List<Dependency> managedDependencies = getProjectManagedDependencies();
            return findManagedVersion(annotationProcessorPath, managedDependencies)
                    .orElseThrow(() -> new MojoExecutionException(String.format(
                            "Cannot find version for annotation processor path '%s'. The version needs to be either"
                                    + " provided directly in the plugin configuration or via dependency management.",
                            annotationProcessorPath)));
        }
    }

    private Optional<String> findManagedVersion(final AnnotationProcessorDependency dependencyCoordinate,
                                                final List<Dependency> managedDependencies) {
        return managedDependencies.stream()
                .filter(dep -> Objects.equals(dep.getGroupId(), dependencyCoordinate.getGroupId())
                        && Objects.equals(dep.getArtifactId(), dependencyCoordinate.getArtifactId())
                        && Objects.equals(dep.getClassifier(), dependencyCoordinate.getClassifier())
                        && Objects.equals(dep.getType(), dependencyCoordinate.getType()))
                .findAny()
                .map(Dependency::getVersion);
    }

    private List<Dependency> getProjectManagedDependencies() {
        return dependencyManagement == null || dependencyManagement.getDependencies() == null
                ? Collections.emptyList()
                : dependencyManagement.getDependencies();
    }

}
