package io.github.potjerodekool.nabu.compiler.daemon.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Downloads a single classifier-specific artifact (e.g. the "linux-x86_64"
 * variant of org.bytedeco:llvm) from a Maven repository, verifies it against
 * the published .sha1 checksum, and caches it locally so subsequent daemon
 * starts don't hit the network again.
 *
 * [baseUrl] defaults to Maven Central but can be pointed at a private
 * repository later (e.g. once custom, single-target-backend LLVM builds are
 * published there) without touching any other part of the launcher.
 */
public class NativeArtifactFetcher {

    private Path cacheRoot;
    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();

    public NativeArtifactFetcher() {
        this(
                Path.of(System.getProperty("user.home"), ".nabu", "native-cache"),
                "https://repo1.maven.org/maven2"
        );
    }

    private NativeArtifactFetcher(final Path cacheRoot,
                                  final String baseUrl) {
        this.cacheRoot = cacheRoot;
        this.baseUrl = baseUrl;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Ensures the artifact described by [coordinates] is present and valid in
     * the local cache, downloading it if necessary. Returns the local path.
     */
    public Path ensure(final Coordinates coordinates) {
        final var target = cacheRoot
                .resolve(coordinates.artifactId())
                .resolve(coordinates.version())
                .resolve(coordinates.fileName());

        if (Files.exists(target) && verify(target, coordinates)) {
            return target;
        }

        download(coordinates.jarUrl(baseUrl), target);

        if (!verify(target, coordinates)) {
            try {
                Files.deleteIfExists(target);
            } catch (final IOException ignored) {
            }
            throw new ArtifactFetchException(String.format("Checksum-verification failed for %s",  coordinates.fileName()));
        }

        return target;
    }

    public Path ensure(final String groupPath,
                       final String artifactId,
                       final String version,
                       final String classifier) {
        return ensure(new Coordinates(groupPath, artifactId, version, classifier));
    }

    private void download(final String url,
                          final Path dest) {
        try {

            Files.createDirectories(dest.getParent());
            final var tmp = Files.createTempFile(dest.getParent(), dest.getFileName().toString(), ".part");

            try {
                final var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
                final var response = http.send(request, HttpResponse.BodyHandlers.ofFile(tmp));

                if (response.statusCode() != 200) {
                    throw new ArtifactFetchException(String.format("Download failed: $url (HTTP %s)", response.statusCode()));
                }
                Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (final IOException| InterruptedException e) {
            throw new ArtifactFetchException(String.format("Download failed: %s", url), e);
        }
    }

    private boolean verify(final Path file,
                           final Coordinates coordinates) {
        final var expected = fetchExpectedSha1(coordinates);

        if (expected == null) {
            return false;
        }

        final var actual = sha1Hex(file);
        return expected.equalsIgnoreCase(actual);
    }

    private String fetchExpectedSha1(final Coordinates coordinates) {
        final var request = HttpRequest.newBuilder(URI.create(coordinates.sha1Url(baseUrl))).GET().build();
        final HttpResponse<String> response;

        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            return null;
        }

        if (response.statusCode() != 200) {
            return null;
        }

        // Some repositories return "<hash>  <filename>", not just the hash.
        final var body = response.body().trim();
        final var sepIndex = body.indexOf(' ');

        if (sepIndex < 0) {
            return body;
        } else {
            return body.substring(0, sepIndex);
        }
    }

    private String sha1Hex(final Path file) {
        try {
            final var digest = MessageDigest.getInstance("SHA-1");

            try (final var input = Files.newInputStream(file)) {
                final var buffer = new byte[8192];
                int read;
                do {
                    read = input.read(buffer);
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                } while (read > 0);

                final var data = digest.digest();
                final var builder = new StringBuilder();

                for (var b : data) {
                    builder.append(String.format("%02x", b));
                }

                return builder.toString();
            }
        } catch (final NoSuchAlgorithmException| IOException e) {
            return null;
        }
    }

    public static class Builder {

        private Path cacheRoot = Path.of(System.getProperty("user.home"), ".nabu", "native-cache");
        private String baseUrl = "https://repo1.maven.org/maven2";

        public Builder cacheRoot(final Path cacheRoot) {
            this.cacheRoot = cacheRoot;
            return this;
        }

        public Builder baseUrl(final String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public NativeArtifactFetcher build() {
            return new NativeArtifactFetcher(cacheRoot, baseUrl);
        }
    }
}
