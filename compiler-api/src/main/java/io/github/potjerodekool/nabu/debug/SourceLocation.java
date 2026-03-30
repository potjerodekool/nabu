package io.github.potjerodekool.nabu.debug;

public record SourceLocation(
        String file,
        int    line,
        int    column
) {
    public static final SourceLocation UNKNOWN = new SourceLocation("<onbekend>", 0, 0);

    public boolean isKnown() { return line > 0; }

    @Override
    public String toString() { return file + ":" + line + ":" + column; }
}
