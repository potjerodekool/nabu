package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A child process in a native executable (Windows): {@code ProcessImpl.start} is replaced by
 * {@link #start} (see {@code ProcessImplSubstitution}). The streams are pipes; a redirect can be
 * {@code PIPE} or {@code INHERIT}. The C runtime does the work through the natives below. Written for
 * bytecode lowering: no string concatenation with {@code +} and no lambdas.
 */
public final class NativeProcess extends Process {

    private static final int STILL_ACTIVE = 259;

    private final long handle;
    private final OutputStream stdin;
    private final InputStream stdout;
    private final InputStream stderr;

    private static native long spawn0(String commandLine, String[] environment, String directory, int[] modes,
                                      long[] fds) throws IOException;

    private static native boolean wait0(long handle, long millis);

    private static native int exit0(long handle);

    private static native void destroy0(long handle);

    private static native int pid0(long handle);

    static native int read0(int fd, byte[] b, int off, int len) throws IOException;

    static native void write0(int fd, byte[] b, int off, int len) throws IOException;

    static native void close0(int fd);

    private NativeProcess(final long handle, final OutputStream stdin, final InputStream stdout,
                          final InputStream stderr) {
        this.handle = handle;
        this.stdin = stdin;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    public static Process start(final String[] cmdarray, final Map<String, String> environment, final String dir,
                                final ProcessBuilder.Redirect[] redirects, final boolean redirectErrorStream)
            throws IOException {
        final int[] modes = new int[4];
        for (int i = 0; i < 3; i++) {
            if (redirects != null && redirects[i] != null) {
                final ProcessBuilder.Redirect.Type type = redirects[i].type();
                if (type == ProcessBuilder.Redirect.Type.INHERIT) {
                    modes[i] = 1;
                } else if (type != ProcessBuilder.Redirect.Type.PIPE) {
                    throw new IOException("This kind of redirect is not supported in a native executable");
                }
            }
        }
        modes[3] = redirectErrorStream ? 1 : 0;

        String[] env = null;
        if (environment != null) {
            final List<String> entries = new ArrayList<String>();
            for (final Map.Entry<String, String> entry : environment.entrySet()) {
                final StringBuilder sb = new StringBuilder();
                sb.append(entry.getKey());
                sb.append('=');
                sb.append(entry.getValue());
                entries.add(sb.toString());
            }
            env = entries.toArray(new String[0]);
        }

        final long[] fds = new long[3];
        final long handle = spawn0(commandLine(cmdarray), env, dir, modes, fds);
        final OutputStream in = fds[0] >= 0 ? new PipeOutput((int) fds[0]) : OutputStream.nullOutputStream();
        final InputStream out = fds[1] >= 0 ? new PipeInput((int) fds[1]) : InputStream.nullInputStream();
        final InputStream err = fds[2] >= 0 ? new PipeInput((int) fds[2]) : InputStream.nullInputStream();
        return new NativeProcess(handle, in, out, err);
    }

    /** The command line the way CommandLineToArgvW reads it back: an argument with a space or quote is quoted. */
    private static String commandLine(final String[] cmdarray) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmdarray.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            appendArgument(sb, cmdarray[i]);
        }
        return sb.toString();
    }

    private static void appendArgument(final StringBuilder sb, final String arg) {
        final boolean quote = arg.isEmpty() || arg.indexOf(' ') >= 0 || arg.indexOf('\t') >= 0
                || arg.indexOf('"') >= 0;
        if (!quote) {
            sb.append(arg);
            return;
        }
        sb.append('"');
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            final char c = arg.charAt(i);
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                for (int k = 0; k < backslashes * 2 + 1; k++) {
                    sb.append('\\');
                }
                sb.append('"');
                backslashes = 0;
            } else {
                for (int k = 0; k < backslashes; k++) {
                    sb.append('\\');
                }
                sb.append(c);
                backslashes = 0;
            }
        }
        for (int k = 0; k < backslashes * 2; k++) {
            sb.append('\\');
        }
        sb.append('"');
    }

    @Override
    public OutputStream getOutputStream() {
        return stdin;
    }

    @Override
    public InputStream getInputStream() {
        return stdout;
    }

    @Override
    public InputStream getErrorStream() {
        return stderr;
    }

    @Override
    public int waitFor() throws InterruptedException {
        wait0(handle, -1L);
        return exit0(handle);
    }

    @Override
    public boolean waitFor(final long timeout, final TimeUnit unit) throws InterruptedException {
        return wait0(handle, unit.toMillis(timeout));
    }

    @Override
    public int exitValue() {
        final int code = exit0(handle);
        if (code == STILL_ACTIVE) {
            throw new IllegalThreadStateException("process has not exited");
        }
        return code;
    }

    @Override
    public void destroy() {
        destroy0(handle);
    }

    @Override
    public Process destroyForcibly() {
        destroy0(handle);
        return this;
    }

    @Override
    public boolean isAlive() {
        return exit0(handle) == STILL_ACTIVE;
    }

    @Override
    public long pid() {
        return pid0(handle);
    }

    @Override
    public boolean supportsNormalTermination() {
        return false;
    }

    /** The read end of a pipe. */
    private static final class PipeInput extends InputStream {
        private int fd;

        PipeInput(final int fd) {
            this.fd = fd;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            final int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (fd < 0) {
                throw new IOException("Stream closed");
            }
            return read0(fd, b, off, len);
        }

        @Override
        public void close() {
            if (fd >= 0) {
                close0(fd);
                fd = -1;
            }
        }
    }

    /** The write end of a pipe. */
    private static final class PipeOutput extends OutputStream {
        private int fd;

        PipeOutput(final int fd) {
            this.fd = fd;
        }

        @Override
        public void write(final int b) throws IOException {
            final byte[] one = new byte[1];
            one[0] = (byte) b;
            write(one, 0, 1);
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            if (fd < 0) {
                throw new IOException("Stream closed");
            }
            write0(fd, b, off, len);
        }

        @Override
        public void close() {
            if (fd >= 0) {
                close0(fd);
                fd = -1;
            }
        }
    }
}
