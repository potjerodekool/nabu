package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JDK code that depends on the generated code and the runtime being right: a char[] store must not
 * touch its neighbour (Path parsing), the most specific default method answers an interface call (streams),
 * Class.getDeclaredField finds instance fields of compiled classes (Random), and the JVM's checks
 * for array indices and integer division. The expected output is what the JVM prints.
 */
class JdkRuntimeE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void jdkClassesRunNatively() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), """
                public class Main {
                    static String hex(byte[] h) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < h.length; i++) {
                            sb.append(Integer.toHexString((h[i] & 0xff) | 0x100).substring(1));
                        }
                        return sb.toString();
                    }

                    public static void main(String[] args) throws Exception {
                        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                            public void run() {
                                System.out.println("hook ran");
                            }
                        }));
                        System.out.println(java.nio.file.Path.of("C:\\\\Users\\\\evert\\\\work").getFileName());
                        System.out.println(String.format(java.util.Locale.ROOT, "%5.2f|%d|%s|%,d", 3.14159, 42, "hi", 1234567));
                        java.util.List<String> words = java.util.Arrays.asList("delta", "alpha", "charlie");
                        System.out.println(words.stream().sorted().collect(java.util.stream.Collectors.toList()) + " "
                                + java.util.stream.IntStream.rangeClosed(1, 10).sum());
                        System.out.println(new java.util.Random(42L).nextInt(100));
                        try {
                            int[] a = new int[1];
                            a[2] = 1;
                        } catch (ArrayIndexOutOfBoundsException e) {
                            System.out.println(e.getMessage());
                        }
                        try {
                            int z = args.length;
                            System.out.println(1 / z);
                        } catch (ArithmeticException e) {
                            System.out.println(e.getMessage());
                        }
                        int minusOne = -1 + args.length;
                        System.out.println(Integer.MIN_VALUE / minusOne);
                        System.out.println(Integer.MIN_VALUE % minusOne);

                        System.out.println(hex(java.security.MessageDigest.getInstance("SHA-256").digest("abc".getBytes())));
                        System.out.println(hex(java.security.MessageDigest.getInstance("SHA-1").digest("abc".getBytes())));
                        System.out.println(hex(java.security.MessageDigest.getInstance("MD5").digest("abc".getBytes())));
                        System.out.println(java.util.UUID.randomUUID().toString().length() + " "
                                + (new java.security.SecureRandom().nextInt(10) >= 0));
                        java.math.BigInteger big = new java.math.BigInteger("123456789012345678901234567890").pow(60);
                        System.out.println(big.bitLength() + " " + big.multiply(big).mod(java.math.BigInteger.valueOf(1000000007L))
                                + " " + new java.math.BigDecimal("12345.6789").multiply(new java.math.BigDecimal("2.5")));
                        System.out.println(1.0 / 3 + " " + 1234567.0 + " " + 12345678.0 + " " + 1.0E-4 + " " + (1.0f / 3.0f) + " "
                                + Math.sqrt(2));
                        java.nio.file.Path file = java.nio.file.Path.of("jdk-runtime-test.txt");
                        java.nio.file.Files.writeString(file, "one\\ntwo\\n");
                        System.out.println(java.nio.file.Files.readAllLines(file) + " " + java.nio.file.Files.size(file));
                        java.nio.file.Files.delete(file);
                        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                        crc.update("abc".getBytes());
                        System.out.println(crc.getValue());
                        System.out.println(java.time.LocalDate.of(2020, 1, 31).plusMonths(1) + " "
                                + (java.time.LocalDate.now().getYear() > 2000) + " "
                                + java.time.ZonedDateTime.of(2020, 6, 1, 12, 0, 0, 0, java.time.ZoneId.of("UTC")).toInstant());
                        System.out.println(new java.text.DecimalFormat("#,##0.00",
                                java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT)).format(1234567.891));
                        Object text = "text";
                        try {
                            Integer bad = (Integer) text;
                            System.out.println("no exception " + bad);
                        } catch (ClassCastException e) {
                            System.out.println("ClassCastException");
                        }
                        StringBuilder log = new StringBuilder();
                        try {
                            try {
                                throw new IllegalStateException("inner");
                            } finally {
                                log.append("f1 ");
                            }
                        } catch (IllegalStateException e) {
                            log.append("caught ");
                        }
                        try {
                            try {
                                Object nothing = null;
                                nothing.toString();
                            } finally {
                                log.append("f2 ");
                            }
                        } catch (NullPointerException e) {
                            log.append("npe");
                        }
                        System.out.println(log);
                        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("walk");
                        java.nio.file.Files.createDirectories(dir.resolve("a/b"));
                        java.nio.file.Files.writeString(dir.resolve("a/b/x.txt"), "x");
                        long entries = java.nio.file.Files.walk(dir).count();
                        java.nio.file.Files.delete(dir.resolve("a/b/x.txt"));
                        java.nio.file.Files.delete(dir.resolve("a/b"));
                        java.nio.file.Files.delete(dir.resolve("a"));
                        java.nio.file.Files.delete(dir);
                        System.out.println(entries);
                        StringBuilder fin = new StringBuilder();
                        fin.append(returning(fin)).append(' ');
                        outer:
                        for (int i = 0; i < 4; i++) {
                            try {
                                try {
                                    if (i == 0) { continue; }
                                    if (i == 2) { continue outer; }
                                    if (i == 3) { break outer; }
                                } finally {
                                    fin.append("i").append(i);
                                }
                            } finally {
                                fin.append("o").append(i).append(' ');
                            }
                        }
                        try {
                            thrower(fin);
                        } catch (RuntimeException e) {
                            fin.append(e.getMessage());
                        }
                        System.out.println(fin);
                        StringBuilder casts = new StringBuilder();
                        Object plain = new Plain();
                        Object lambda = (Runnable) () -> { };
                        Object ints = new int[1];
                        Object strings = new String[1];
                        try { Marker m = (Marker) plain; casts.append("bad "); } catch (ClassCastException e) { casts.append("marker "); }
                        try { Runnable r = (Runnable) plain; casts.append("bad "); } catch (ClassCastException e) { casts.append("runnable "); }
                        try { String[] a = (String[]) ints; casts.append("bad "); } catch (ClassCastException e) { casts.append("array "); }
                        try { Marker m = (Marker) new Marked(); Runnable r = (Runnable) lambda; Object[] a = (Object[]) strings;
                            CharSequence[] c = (CharSequence[]) strings; int[] i = (int[]) ints; casts.append("ok"); }
                        catch (ClassCastException e) { casts.append("wrong"); }
                        System.out.println(casts);
                        Process child = new ProcessBuilder("cmd", "/c", "echo", "child").redirectErrorStream(true).start();
                        System.out.println(new String(child.getInputStream().readAllBytes()).trim() + " " + child.waitFor());
                        java.io.File raf = java.io.File.createTempFile("raf", ".bin");
                        try (java.io.RandomAccessFile rafFile = new java.io.RandomAccessFile(raf, "rw")) {
                            rafFile.writeInt(42);
                            rafFile.writeUTF("hi");
                            rafFile.seek(0);
                            System.out.println(rafFile.readInt() + rafFile.readUTF() + " " + rafFile.length());
                        }
                        java.nio.file.Path from = raf.toPath();
                        java.nio.file.Path to = from.resolveSibling(from.getFileName() + ".copy");
                        java.nio.file.Files.copy(from, to);
                        java.nio.file.attribute.BasicFileAttributes attributes =
                                java.nio.file.Files.readAttributes(to, java.nio.file.attribute.BasicFileAttributes.class);
                        System.out.println(attributes.size() + " " + attributes.isRegularFile() + " " + attributes.isDirectory());
                        java.nio.file.Files.delete(to);
                        raf.delete();
                        System.out.println(new Many("a", "b", "c").count + " " + new Many().count);
                        java.nio.file.Path channelFile = java.nio.file.Files.createTempFile("chan", ".bin");
                        StringBuilder channels = new StringBuilder();
                        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(channelFile,
                                java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.READ)) {
                            channels.append(channel.write(java.nio.ByteBuffer.wrap("hello channel".getBytes()))).append(' ')
                                    .append(channel.size());
                            channel.position(6);
                            java.nio.ByteBuffer back = java.nio.ByteBuffer.allocate(7);
                            channel.read(back);
                            channels.append(' ').append(new String(back.array()));
                            channel.truncate(5);
                            channels.append(' ').append(channel.size());
                        }
                        try (java.nio.channels.SeekableByteChannel input = java.nio.file.Files.newByteChannel(channelFile)) {
                            java.nio.ByteBuffer direct = java.nio.ByteBuffer.allocateDirect(16);
                            int count = input.read(direct);
                            direct.flip();
                            channels.append(' ').append(count).append(' ').append(direct.get(0));
                        }
                        java.nio.file.Files.delete(channelFile);
                        System.out.println(channels);
                        java.nio.file.Path walkDir = java.nio.file.Files.createTempDirectory("walk");
                        java.nio.file.Path walkA = java.nio.file.Files.createFile(walkDir.resolve("a.txt"));
                        java.nio.file.Path walkSub = java.nio.file.Files.createDirectory(walkDir.resolve("sub"));
                        java.nio.file.Path walkB = java.nio.file.Files.createFile(walkSub.resolve("b.json"));
                        java.util.List<String> walked = new java.util.ArrayList<>();
                        java.nio.file.Files.walkFileTree(walkDir, new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                            @Override
                            public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                                    java.nio.file.attribute.BasicFileAttributes attrs) {
                                walked.add(file.getFileName().toString());
                                return java.nio.file.FileVisitResult.CONTINUE;
                            }
                        });
                        java.util.Collections.sort(walked);
                        java.nio.file.Files.setLastModifiedTime(walkA, java.nio.file.attribute.FileTime.fromMillis(1000000000000L));
                        java.nio.file.Files.setAttribute(walkA, "dos:readonly", true);
                        java.nio.file.attribute.DosFileAttributes dos =
                                java.nio.file.Files.readAttributes(walkA, java.nio.file.attribute.DosFileAttributes.class);
                        boolean readOnly = dos.isReadOnly();
                        java.nio.file.Files.setAttribute(walkA, "dos:readonly", false);
                        System.out.println(String.join(" ", walked) + " " + java.nio.file.Files.getLastModifiedTime(walkA).toMillis()
                                + " " + readOnly + " " + java.nio.file.Files.probeContentType(walkA)
                                + " " + java.nio.file.Files.probeContentType(walkB)
                                + " " + (java.nio.file.Files.getOwner(walkA).getName().length() > 0)
                                + " " + walkA.toRealPath().getFileName()
                                + " " + (java.nio.file.Files.getFileStore(walkA).getTotalSpace() > 0));
                        java.nio.file.Files.delete(walkA);
                        java.nio.file.Files.delete(walkB);
                        java.nio.file.Files.delete(walkSub);
                        java.nio.file.Files.delete(walkDir);
                        crypto();
                    }

                    static void crypto() throws Exception {
                        java.util.HexFormat hex = java.util.HexFormat.of();
                        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                        mac.init(new javax.crypto.spec.SecretKeySpec("key".getBytes(), "HmacSHA256"));
                        String hmac = hex.formatHex(mac.doFinal("data".getBytes()));
                        javax.crypto.spec.SecretKeySpec aesKey = new javax.crypto.spec.SecretKeySpec(new byte[16], "AES");
                        javax.crypto.Cipher cbc = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
                        cbc.init(javax.crypto.Cipher.ENCRYPT_MODE, aesKey, new javax.crypto.spec.IvParameterSpec(new byte[16]));
                        byte[] encrypted = cbc.doFinal("hello world".getBytes());
                        cbc.init(javax.crypto.Cipher.DECRYPT_MODE, aesKey, new javax.crypto.spec.IvParameterSpec(new byte[16]));
                        String decrypted = new String(cbc.doFinal(encrypted));
                        javax.crypto.Cipher gcm = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                        gcm.init(javax.crypto.Cipher.ENCRYPT_MODE, aesKey, new javax.crypto.spec.GCMParameterSpec(128, new byte[12]));
                        byte[] sealed = gcm.doFinal("hello".getBytes());
                        gcm.init(javax.crypto.Cipher.DECRYPT_MODE, aesKey, new javax.crypto.spec.GCMParameterSpec(128, new byte[12]));
                        String opened = new String(gcm.doFinal(sealed));
                        byte[] derived = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                                .generateSecret(new javax.crypto.spec.PBEKeySpec("pw".toCharArray(), new byte[8], 1000, 128)).getEncoded();
                        javax.crypto.KeyGenerator generator = javax.crypto.KeyGenerator.getInstance("AES");
                        generator.init(128);
                        System.out.println(hmac + " " + hex.formatHex(encrypted) + " " + decrypted + " " + hex.formatHex(sealed) + " "
                                + opened + " " + hex.formatHex(derived) + " " + generator.generateKey().getEncoded().length
                                + " " + Class.forName("java.util.List").isInterface());
                    }

                    static class Many {
                        final int count;
                        Many(String... parts) { this.count = parts.length; }
                    }

                    interface Marker { }
                    static class Plain { }
                    static class Marked implements Marker { }

                    static int returning(StringBuilder log) {
                        try {
                            try {
                                return 7;
                            } finally {
                                log.append("a");
                            }
                        } finally {
                            log.append("b");
                        }
                    }

                    static void thrower(StringBuilder log) {
                        try {
                            throw new IllegalStateException();
                        } catch (IllegalStateException e) {
                            log.append("c");
                            throw new RuntimeException("x");
                        } finally {
                            log.append("f");
                        }
                    }
                }
                """, StandardCharsets.UTF_8);
        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);
        var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toString())
                .option(CompilerOption.BACKEND, "LLVM")
                .option(CompilerOption.GC, "BOEHM")
                .option(CompilerOption.SYSTEM, new File(root, "compiler/src/test/resources").getAbsolutePath())
                .build();
        var diagnostics = new java.util.ArrayList<String>();
        var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(d.getKind() + ": " + d.getMessage(java.util.Locale.getDefault())));
        assertEquals(0, compiler.compile(options), String.join("\n", diagnostics));
        Path exe;
        try (Stream<Path> walk = Files.walk(out)) {
            exe = walk.filter(p -> p.getFileName().toString().endsWith(".exe")).findFirst().orElseThrow();
        }
        final String keep = System.getProperty("nabu.keepExe");
        if (keep != null) {
            Files.copy(exe, Path.of(keep), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (Stream<Path> walk = Files.walk(out)) {
                final Path ll = walk.filter(p -> p.getFileName().toString().endsWith(".ll"))
                        .max(java.util.Comparator.comparingLong(p -> p.toFile().length())).orElse(null);
                if (ll != null) {
                    Files.copy(ll, Path.of(keep.substring(0, keep.length() - ".exe".length()) + ".ll"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        final Path log = tempDir.resolve("run.log");
        Process proc = NativeToolchain.run(exe.toAbsolutePath()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("programma hangt; output tot nu toe:\n"
                    + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        }
        String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        int exit = proc.exitValue();
        assertEquals("work\n 3.14|42|hi|1,234,567\n[alpha, charlie, delta] 55\n30\nIndex 2 out of bounds for length 1\n/ by zero\n-2147483648\n0\nba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\na9993e364706816aba3e25717850c26c9cd0d89d\n900150983cd24fb0d6963f7d28e17f72\n36 true\n5799 38616657 30864.19725\n0.3333333333333333 1234567.0 1.2345678E7 1.0E-4 0.33333334 1.4142135623730951\n[one, two] 8\n891568578\n2020-02-29 true 2020-06-01T12:00:00Z\n1,234,567.89\nClassCastException\nf1 caught f2 npe\n4\nab7 i0o0 i1o1 i2o2 i3o3 cfx\nmarker runnable array ok\nchild 0\n42hi 8\n8 true false\n3 0\n13 13 channel 5 5 104\na.txt b.json 1000000000000 true text/plain application/json true a.txt true\n5031fe3d989c6d1537a013fa6e739da23463fdaec3b70137d828e36ace221bd0 7489adda96bb9c30fb4932e07731571a hello world 6bedb6a20ffc05f3475d29034caa1cc8fa3628c39f hello f8cffa25bbacb89251a7dd602940e06c 16 true\nhook ran\n", output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}
