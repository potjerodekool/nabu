package io.github.potjerodekool.nabu.maven;

import java.util.Set;

public class AnnotationProcessorDependency {

    private String groupId;

    private String artifactId;

    private String version;

    private String type = "jar";

    private String classifier;

    private Set<DependencyExclusion> exclusions;

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(final String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(final String artifactId) {
        this.artifactId = artifactId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(final String version) {
        this.version = version;
    }

    public String getType() {
        return type;
    }

    public void setType(final String type) {
        this.type = type;
    }

    public String getClassifier() {
        return classifier;
    }

    public void setClassifier(final String classifier) {
        this.classifier = classifier;
    }

    public Set<DependencyExclusion> getExclusions() {
        return exclusions;
    }

    public void setExclusions(final Set<DependencyExclusion> exclusions) {
        this.exclusions = exclusions;
    }
}
