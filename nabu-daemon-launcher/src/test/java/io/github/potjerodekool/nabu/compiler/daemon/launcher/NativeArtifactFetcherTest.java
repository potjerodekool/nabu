package io.github.potjerodekool.nabu.compiler.daemon.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.NamedExecutable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NativeArtifactFetcherTest {

    private HttpServer server;
    private String baseUrl;
    private AtomicInteger jarRequests = new AtomicInteger(0);
    private byte[] jarBytes = "fake-native-jar-content-v1".getBytes();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);

        server.createContext("/org/bytedeco/llvm/1.0.0/llvm-1.0.0-linux-x86_64.jar", new HttpHandler() {
            @Override
            public void handle(final HttpExchange exchange) throws IOException {
                jarRequests.incrementAndGet();
                exchange.sendResponseHeaders(200, jarBytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(jarBytes);
                }
            }
        });

        server.createContext("/org/bytedeco/llvm/1.0.0/llvm-1.0.0-linux-x86_64.jar.sha1", new HttpHandler() {
            @Override
            public void handle(final HttpExchange exchange) throws IOException {
                final var sha1 = sha1Hex(jarBytes);
                final var body = sha1.getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            }
        });

        server.createContext("/org/bytedeco/llvm/1.0.0/llvm-1.0.0-corrupt-classifier.jar", new HttpHandler() {
            @Override
            public void handle(final HttpExchange exchange) throws IOException {
                final var body = "wrong-content".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            }
        });

        server.createContext("/org/bytedeco/llvm/1.0.0/llvm-1.0.0-corrupt-classifier.jar.sha1", new HttpHandler() {
            @Override
            public void handle(final HttpExchange exchange) throws IOException {
                // Deliberately does NOT match the jar content above.
                final var body = "0000000000000000000000000000000000000000".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            }
        });

        server.start();
        baseUrl = String.format("http://localhost:%s", server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void downloadsVerifiesAndCachesAnArtifact(@TempDir final Path tempDir) throws IOException {
        final var fetcher = NativeArtifactFetcher.builder()
                .cacheRoot(tempDir)
                .baseUrl(baseUrl)
                .build();

        final var path = fetcher.ensure("org/bytedeco", "llvm", "1.0.0", "linux-x86_64");

        assertTrue(Files.exists(path));
        assertEquals(new String(jarBytes), Files.readString(path));
        assertEquals(1, jarRequests.get());
    }

    @Test
    void doesNotReDownloadWhenAValidCachedCopyAlreadyExists(@TempDir final Path tempDir) {
        final var fetcher = NativeArtifactFetcher.builder()
                .cacheRoot(tempDir)
                .baseUrl(baseUrl)
                .build();

        fetcher.ensure("org/bytedeco", "llvm", "1.0.0", "linux-x86_64");
        fetcher.ensure("org/bytedeco", "llvm", "1.0.0", "linux-x86_64");

        assertEquals(1, jarRequests.get(), "tweede aanroep had de cache moeten gebruiken");
    }

    @Test
    void reDownloadsWhenTheCachedCopyIsCorruptedOrStale(@TempDir final Path tempDir) throws IOException {
        final var fetcher = NativeArtifactFetcher.builder()
                .cacheRoot(tempDir)
                .baseUrl(baseUrl)
                .build();

        final var path = fetcher.ensure("org/bytedeco", "llvm", "1.0.0", "linux-x86_64");
        // Simulate local corruption (e.g. truncated disk write).
        Files.writeString(path, "corrupted-on-disk");

        fetcher.ensure("org/bytedeco", "llvm", "1.0.0", "linux-x86_64");

        assertEquals(2, jarRequests.get());
        assertEquals(new String(jarBytes), Files.readString(path));
    }

    @Test
    void throwsWhenTheDownloadedArtifactFailsChecksumVerification(@TempDir final Path tempDir) {
        final var fetcher = NativeArtifactFetcher.builder()
                .cacheRoot(tempDir)
                .baseUrl(baseUrl)
                .build();

        assertThrows(ArtifactFetchException.class, (NamedExecutable) () -> {
            fetcher.ensure("org/bytedeco", "llvm", "1.0.0", "corrupt-classifier");
        });
    }

    private String sha1Hex(final byte[] bytes) {
        final byte[] digested;
        try {
            digested = MessageDigest.getInstance("SHA-1").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        final var builder = new StringBuilder();

        for (final var b : digested) {
            builder.append("%02x".formatted(b));
        }

        return builder.toString();
    }


}