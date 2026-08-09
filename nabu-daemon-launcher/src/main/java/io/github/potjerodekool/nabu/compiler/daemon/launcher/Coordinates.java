package io.github.potjerodekool.nabu.compiler.daemon.launcher;

public record Coordinates(String groupPath,
                          String artifactId,
                          String version,
                          String classifier) {

    public String fileName() {
        return String.format("%s-%s-%s.jar", artifactId, version, classifier);
    }

    public String jarUrl(final String base) {
        return String.format("%s/%s/%s/%s/%s", base, groupPath, artifactId, version, fileName());
    }

    public String sha1Url(final String base) {
        return String.format("%s.sha1", jarUrl(base));
    }


}
