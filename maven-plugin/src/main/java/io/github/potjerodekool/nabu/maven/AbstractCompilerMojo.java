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
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.util.artifact.JavaScopes;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Parameter(property = "maven.compiler.target", defaultValue = "17")
    protected String targetVersion;

    /**
     * The Java version (17 up to 25) for the language level and the class file version. A language feature of a newer
     * version is an error. Replaces {@code target}.
     */
    @Parameter(property = "maven.compiler.release")
    protected String release;

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

    @Parameter(property = "nabu.backend", defaultValue = "ASM")
    protected String backend;

    @Parameter(property = "nabu.incremental", defaultValue = "true")
    protected boolean incremental;

    /**
     * Compile with the Nabu compiler daemon, a JVM that stays alive between builds. Maven creates the class loader of
     * this plugin again for every build, so the compiler is cold (the first parse of every construct of the Java and
     * Nabu grammars is slow) in every build; the daemon keeps it warm. The daemon is started when it is not running.
     * When it cannot be used the compilation happens in the process of Maven, as without this option.
     */
    @Parameter(property = "nabu.daemon", defaultValue = "false")
    protected boolean daemon;

    /**
     * The port of the daemon, on the loopback address only.
     */
    @Parameter(property = "nabu.daemon.port", defaultValue = "9876")
    protected int daemonPort;

    /**
     * The daemon jar. Default is the nabu-daemon artifact of the version of this plugin.
     */
    @Parameter(property = "nabu.daemon.jar")
    protected File daemonJar;

    /**
     * Arguments for the JVM of the daemon, separated by spaces (for example {@code -Xmx2g}).
     */
    @Parameter(property = "nabu.daemon.jvmArgs")
    protected String daemonJvmArgs;

    @Parameter(defaultValue = "${plugin.version}", readonly = true)
    private String pluginVersion;

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

        final var outputDir = getOutputDirectory();
        if (outputDir != null && !outputDir.exists() && !outputDir.mkdirs()) {
            throw new MojoExecutionException("Cannot create output directory: " + outputDir);
        }

        configureLogging();

        try {
            final var compilerOptions = configureOptions(existingSourceDirs);
            var resultCode = daemon ? compileWithDaemon(compilerOptions) : null;

            if (resultCode == null) {
                resultCode = new NabuCompiler().compile(compilerOptions);
            }

            if (resultCode != 0 && failOnError) {
                throw new MojoExecutionException("Compilation failure");
            }

        } catch (final Exception e) {
            throw new MojoExecutionException("Failed to compile " + getCompilationType() + " sources", e);
        }
    }

    /**
     * @return The result of the compilation by the daemon, or null if there is no daemon to compile with.
     */
    private Integer compileWithDaemon(final CompilerOptions compilerOptions) {
        final Path jar;

        try {
            jar = resolveDaemonJar();
        } catch (final Exception e) {
            getLog().warn("The Nabu daemon jar is not available (" + e.getMessage() + "), compiling without the daemon");
            return null;
        }

        final var jvmArgs = daemonJvmArgs == null || daemonJvmArgs.isBlank()
                ? List.<String>of()
                : List.of(daemonJvmArgs.trim().split("\\s+"));

        final var outcome = new DaemonCompiler(getLog(), daemonPort, jar, jvmArgs).compile(compilerOptions);

        return switch (outcome) {
            case SUCCESS -> 0;
            case FAILED -> 1;
            case UNAVAILABLE -> {
                getLog().warn("Compiling without the Nabu daemon");
                yield null;
            }
        };
    }

    private Path resolveDaemonJar() throws Exception {
        if (daemonJar != null) {
            if (!daemonJar.isFile()) {
                throw new MojoExecutionException("nabu.daemon.jar does not exist: " + daemonJar);
            }
            return daemonJar.toPath();
        }

        final var artifact = new DefaultArtifact("io.github.potjerodekool", "nabu-daemon", "jar", pluginVersion);
        final var request = new ArtifactRequest(artifact, remoteProjectRepositories, null);
        final var result = repositorySystem.resolveArtifact(session.getRepositorySession(), request);
        return result.getArtifact().getFile().toPath();
    }

    private CompilerOptions configureOptions(final List<File> existingSourceDirs) throws MojoExecutionException {
        final var compilerOptionsBuilder = new CompilerOptions.CompilerOptionsBuilder();

        final var outputDirectory = getOutputDirectory().getAbsolutePath();

        configureClassPath(compilerOptionsBuilder);
        configureSourceRoots(existingSourceDirs, compilerOptionsBuilder);
        configureAnnotationProcessorsPaths(compilerOptionsBuilder);
        configureBackend(compilerOptionsBuilder);
        // compilerArgs winnen over de expliciete mojo-parameter (voor als een
        // build zowel <backend> als -backend:... meegeeft).
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

        compilerOptionsBuilder.option(
                CompilerOption.INCREMENTAL,
                String.valueOf(incremental)
        );

        if (release != null && !release.isBlank()) {
            // --release replaces --target (target has a default, so it is skipped here).
            compilerOptionsBuilder.option(CompilerOption.RELEASE, release.trim());
        } else if (targetVersion != null) {
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

    private void configureBackend(final CompilerOptions.CompilerOptionsBuilder compilerOptionsBuilder) {
        if (backend == null) {
            return;
        }
        compilerOptionsBuilder.option(CompilerOption.BACKEND, backend);
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

           compilerOptionsBuilder.option(create(name), value);
        });

    }

    static CompilerOption create(final String name) {
        // Compare without the leading dashes on both sides: `--system:dir`,
        // `system:dir` and `-d` all name a known option.
        final var wanted = stripDashes(name.trim());
        final var optionOptional = CompilerOption.COMPILER_OPTIONS.stream()
                .filter(option -> stripDashes(option.optionName().trim()).equals(wanted))
                .findFirst();

        if  (optionOptional.isPresent()) {
            return optionOptional.get();
        }

        return new CompilerOption(name);
    }

    private static String stripDashes(final String optionName) {
        int start = 0;
        while (start < optionName.length() && optionName.charAt(start) == '-') {
            start++;
        }
        return optionName.substring(start);
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
