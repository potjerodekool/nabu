package io.github.potjerodekool.nabu.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.util.List;

/**
 * A maven mojo that compiles test sources.
 */
@Mojo(
        name = "testCompile",
        defaultPhase = LifecyclePhase.TEST_COMPILE,
        requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = true
)
public class TestCompileMojo extends AbstractCompilerMojo {

    @Parameter(defaultValue = "${project.testCompileSourceRoots}", readonly = true, required = true)
    private List<String> testCompileSourceRoots;

    @Parameter(defaultValue = "${project.testClasspathElements}", readonly = true)
    private List<String> testClasspathElements;

    @Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true, readonly = true)
    private File testOutputDirectory;

    @Parameter(property = "maven.test.skip", defaultValue = "false")
    private boolean skipTests;

    @Parameter(property = "skipTests", defaultValue = "false")
    private boolean skip;

    @Parameter(defaultValue = "${project.build.directory}/generated-test-sources/annotations")
    private String generatedSourcesDirectory;

    @Override
    protected List<String> getSourceRoots() {
        return testCompileSourceRoots;
    }

    @Override
    protected File getOutputDirectory() {
        return testOutputDirectory;
    }

    @Override
    protected List<String> getClasspathElements() {
        return testClasspathElements;
    }

    @Override
    public String getGeneratedSourcesDirectory() {
        return generatedSourcesDirectory;
    }

    @Override
    protected String getCompilationType() {
        return "test";
    }

    public void execute() throws MojoExecutionException {
        if (skipTests || skip) {
            getLog().info("Skipping test compilation");
            return;
        }
        super.execute();
    }
}
