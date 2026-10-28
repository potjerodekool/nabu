package io.github.potjerodekool.nabu.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * The GitHub releases of Nabu: {@code <base>/latest} redirects to {@code <base>/tag/<tag>}, and the files of a release
 * are at {@code <base>/download/<tag>/<file>}. That needs no API and no token, and has no rate limit to speak of.
 */
final class Releases {

    static final String DEFAULT_URL = "https://github.com/potjerodekool/nabu/releases";

    private final String base;
    private final HttpClient following = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();
    private final HttpClient direct = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(30)).build();

    Releases(final String base) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** The tag of the latest release, for example {@code v0.2.0}. */
    String latestTag() throws IOException, InterruptedException {
        final HttpResponse<Void> response = direct.send(
                HttpRequest.newBuilder(URI.create(base + "/latest")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        final String location = response.headers().firstValue("Location").orElse(null);
        if (response.statusCode() / 100 != 3 || location == null) {
            throw new IOException("no release found at " + base + "/latest (HTTP " + response.statusCode() + ")");
        }
        final int tag = location.lastIndexOf("/tag/");
        if (tag < 0) {
            throw new IOException("there is no release to update to: " + base + "/latest leads to " + location
                    + ", and GitHub does not count pre-releases as the latest release (use --version <version>)");
        }
        return location.substring(tag + "/tag/".length());
    }

    String url(final String tag, final String file) {
        return base + "/download/" + tag + "/" + file;
    }

    /** Downloads a file of a release; the target only exists when the download is complete. */
    void download(final String tag, final String file, final Path target, final PrintStream out)
            throws IOException, InterruptedException {
        final HttpResponse<InputStream> response = following.send(
                HttpRequest.newBuilder(URI.create(url(tag, file))).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException(url(tag, file) + ": HTTP " + response.statusCode());
        }
        final long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        final Path partial = target.resolveSibling(target.getFileName() + ".part");
        out.println("  downloading " + file + (length >= 0 ? " (" + megabytes(length) + ")" : ""));
        try (InputStream in = response.body()) {
            final byte[] buffer = new byte[1 << 16];
            long done = 0;
            int printed = 0;
            try (var file0 = Files.newOutputStream(partial)) {
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    file0.write(buffer, 0, read);
                    done += read;
                    if (length > 100_000_000) {
                        final int percent = (int) (done * 100 / length) / 20 * 20;
                        if (percent > printed) {
                            printed = percent;
                            out.println("    " + percent + "%");
                        }
                    }
                }
            }
        } catch (final IOException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String megabytes(final long bytes) {
        return (bytes + 524_288) / 1_048_576 + " MB";
    }
}
