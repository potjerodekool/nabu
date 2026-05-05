package io.github.potjerodekool.tools.model;

public record JDK(String preferredDistribution,
                  int minimalVersion,
                  Integer maximalVersion, Integer recommendedVersion) {


}
