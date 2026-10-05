package io.github.potjerodekool.nabu.compiler.backend.native_llvm.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON-lezer voor de GraalVM-native-image-configuraties: escapes, codepoints
 * buiten het BMP en getalprecisie.
 */
class TinyJsonTest {

    @Test
    void parsesStandardEscapes() {
        assertEquals("a\"b\\c/d\ne\tf\rg\bh\fi",
                TinyJson.parse("\"a\\\"b\\\\c\\/d\\ne\\tf\\rg\\bh\\fi\""));
    }

    @Test
    void parsesUnicodeEscape() {
        assertEquals("A", TinyJson.parse("\"\\u0041\""));
        assertEquals("\u00e9", TinyJson.parse("\"\\u00e9\""));
        assertEquals("caf\u00e9", TinyJson.parse("\"caf\\u00e9\""));
    }

    @Test
    void parsesSurrogatePairAsSingleCodePoint() {
        final var parsed = TinyJson.parse("\"\\uD83D\\uDE00\"");

        assertEquals("\uD83D\uDE00", parsed);
        assertEquals(1, ((String) parsed).codePointCount(0, ((String) parsed).length()));
        assertEquals(0x1F600, ((String) parsed).codePointAt(0));
    }

    @Test
    void rejectsHighSurrogateWithoutLowSurrogate() {
        assertThrows(IllegalArgumentException.class, () -> TinyJson.parse("\"\\uD83D\""));
    }

    @Test
    void rejectsUnknownEscape() {
        assertThrows(IllegalArgumentException.class, () -> TinyJson.parse("\"\\q\""));
    }

    @Test
    void keepsIntegerPrecision() {
        final var parsed = TinyJson.parse("""
                { "big" : 9007199254740993, "small" : 42, "neg" : -7 }""");

        assertInstanceOf(Map.class, parsed);

        @SuppressWarnings("unchecked")
        final var map = (Map<String, Object>) parsed;

        // 2^53 + 1: als dit een Double zou zijn, zou het afronden op ...992.
        assertEquals(9007199254740993L, map.get("big"));
        assertEquals(42L, map.get("small"));
        assertEquals(-7L, map.get("neg"));
    }

    @Test
    void parsesFractionsAndExponentsAsDouble() {
        final var parsed = TinyJson.parse("""
                [ 1.5, 2e3, -0.25E-2 ]""");

        assertInstanceOf(List.class, parsed);

        @SuppressWarnings("unchecked")
        final var list = (List<Object>) parsed;

        assertEquals(1.5, (Double) list.get(0), 1e-9);
        assertEquals(2000.0, (Double) list.get(1), 1e-9);
        assertEquals(-0.0025, (Double) list.get(2), 1e-12);
    }

    @Test
    void rejectsTrailingContent() {
        assertThrows(IllegalArgumentException.class, () -> TinyJson.parse("{} garbage"));
        assertThrows(IllegalArgumentException.class, () -> TinyJson.parse("[1] [2]"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> TinyJson.parse(""));
        assertThrows(IllegalArgumentException.class, () -> TinyJson.parse("\"niet afgesloten"));
        assertThrows(IllegalArgumentException.class, () -> TinyJson.parse("{\"a\" 1}"));
        assertThrows(IllegalArgumentException.class, () -> TinyJson.parse("[1,]"));
        assertThrows(IllegalArgumentException.class, () -> TinyJson.parse("tru"));
        assertThrows(IllegalArgumentException.class, () -> TinyJson.parse("01x"));
        assertThrows(IllegalArgumentException.class, () -> TinyJson.parse("1."));
    }

    @Test
    void reportsFailureForUnclosedContainers() {
        assertThrows(IllegalArgumentException.class, () -> TinyJson.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> TinyJson.parse("["));
    }

    @Test
    void parsesEmptyContainers() {
        assertEquals(Map.of(), TinyJson.parse("{}"));
        assertEquals(List.of(), TinyJson.parse("[ ]"));
    }

    @Test
    void keepsBooleansAndNullDistinct() {
        final var parsed = TinyJson.parse("""
                { "t" : true, "f" : false, "n" : null }""");

        assertInstanceOf(Map.class, parsed);

        @SuppressWarnings("unchecked")
        final var map = (Map<String, Object>) parsed;

        assertEquals(Boolean.TRUE, map.get("t"));
        assertEquals(Boolean.FALSE, map.get("f"));
        assertTrue(map.containsKey("n"));
        assertEquals(null, map.get("n"));
    }
}
