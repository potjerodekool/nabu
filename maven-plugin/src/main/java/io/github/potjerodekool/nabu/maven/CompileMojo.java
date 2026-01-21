package io.github.potjerodekool.nabu.maven;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * A maven mojo that compiles main sources.
 */
@Mojo(
        name = "compile",
        defaultPhase = LifecyclePhase.COMPILE,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        threadSafe = true
)
public class CompileMojo extends AbstractCompilerMojo {

    @Parameter(defaultValue = "${project.compileSourceRoots}", readonly = true, required = true)
    private List<String> compileSourceRoots;

    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true, readonly = true)
    private File outputDirectory;

    @Parameter(defaultValue = "${project.compileClasspathElements}", readonly = true)
    private List<String> compileClasspathElements;


    @Parameter(defaultValue = "${project.build.directory}/generated-sources/annotations")
    private String generatedSourcesDirectory;

    /**
     * Create new instance.
     */
    public CompileMojo() {
    }

    @Override
    protected List<String> getSourceRoots() {
        return compileSourceRoots;
    }

    @Override
    public File getOutputDirectory() {
        return outputDirectory;
    }

    @Override
    protected List<String> getClasspathElements() {
        return compileClasspathElements;
    }

    @Override
    public String getGeneratedSourcesDirectory() {
        return generatedSourcesDirectory;
    }

    @Override
    protected String getCompilationType() {
        return "main";
    }
}

