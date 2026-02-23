package io.github.potjerodekool.nabu.compiler.daemon;

public final class Protocol {

    // Commands
    public static final byte CMD_COMPILE = 0x01;
    public static final byte CMD_PING = 0x02;
    public static final byte CMD_SHUTDOWN = 0x03;

    // Diagnostic
    public static final byte DIAGNOSTIC_ERROR = 0x10;
    public static final byte DIAGNOSTIC_WARN = 0x11;
    public static final byte DIAGNOSTIC_MANDATORY_WARNING = 0x12;
    public static final byte DIAGNOSTIC_NOTE = 0x13;
    public static final byte DIAGNOSTIC_OTHER = 0x14;

    //Status
    public static final byte STATUS_SUCCESS = 0x20;
    public static final byte STATUS_ERROR = 0x21;
    public static final byte STATUS_COMPILE_STARTED = 0x22;
    public static final byte STATUS_END = 0x23;

    private Protocol() {
    }
}
