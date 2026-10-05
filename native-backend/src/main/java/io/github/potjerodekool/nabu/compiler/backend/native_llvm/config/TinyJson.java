package io.github.potjerodekool.nabu.compiler.backend.native_llvm.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimale JSON-lezer voor de GraalVM-native-image-configuraties.
 * Ondersteunt objecten, arrays, strings, booleans, getallen en null —
 * zonder externe dependency.
 */
final class TinyJson {

    private final String source;
    private int position;

    private TinyJson(final String source) {
        this.source = source;
    }

    static Object parse(final String json) {
        final var parser = new TinyJson(json);
        parser.skipWhitespace();
        final var value = parser.parseValue();
        parser.skipWhitespace();

        if (!parser.atEnd()) {
            throw new IllegalArgumentException(
                    "Trailing inhoud na de JSON-waarde bij position " + parser.position);
        }

        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (atEnd()) {
            throw new IllegalArgumentException("EOF bij JSON-parse");
        }
        final var c = current();
        switch (c) {
            case '{':
                return parseObject();
            case '[':
                return parseArray();
            case '"':
                return parseString();
            case 't':
                expectWord("true");
                return Boolean.TRUE;
            case 'f':
                expectWord("false");
                return Boolean.FALSE;
            case 'n':
                expectWord("null");
                return null;
            default:
                return parseNumber();
        }
    }

    private Map<String, Object> parseObject() {
        final var result = new LinkedHashMap<String, Object>();
        position++; // '{'
        skipWhitespace();
        if (atEnd()) {
            throw new IllegalArgumentException("Niet-afgesloten object");
        }
        if (current() == '}') {
            position++;
            return result;
        }
        while (true) {
            skipWhitespace();
            final var key = parseString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            result.put(key, parseValue());
            skipWhitespace();
            final var c = current();
            if (c == ',') {
                position++;
                skipWhitespace();
            } else if (c == '}') {
                position++;
                break;
            } else {
                throw new IllegalArgumentException("Onverwacht teken '" + c + "' in object bij position " + position);
            }
        }
        return result;
    }

    private List<Object> parseArray() {
        final var result = new ArrayList<Object>();
        position++; // '['
        skipWhitespace();
        if (atEnd()) {
            throw new IllegalArgumentException("Niet-afgesloten array");
        }
        if (current() == ']') {
            position++;
            return result;
        }
        while (true) {
            result.add(parseValue());
            skipWhitespace();
            final var c = current();
            if (c == ',') {
                position++;
                skipWhitespace();
            } else if (c == ']') {
                position++;
                break;
            } else {
                throw new IllegalArgumentException("Onverwacht teken '" + c + "' in array bij position " + position);
            }
        }
        return result;
    }

    private String parseString() {
        final var sb = new StringBuilder();
        position++; // '"'
        while (position < source.length()) {
            final var c = source.charAt(position++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (atEnd()) {
                    throw new IllegalArgumentException("Niet-afgesloten escape in string");
                }
                final var escape = source.charAt(position++);
                switch (escape) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> sb.append(parseUnicodeEscape());
                    default -> throw new IllegalArgumentException(
                            "Onbekende escape '\\" + escape + "' bij position " + position);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("Niet-afgesloten string");
    }

    /**
     * Leest een \\uXXXX-escape. Een high surrogate wordt direct gevolgd door de
     * bijbehorende low surrogate; samen vormen ze één codepoint.
     */
    private String parseUnicodeEscape() {
        final var high = readHexQuad();

        if (!Character.isHighSurrogate(high)) {
            return String.valueOf(high);
        }

        if (position + 2 > source.length()
                || source.charAt(position) != '\\'
                || source.charAt(position + 1) != 'u') {
            throw new IllegalArgumentException(
                    "High surrogate zonder low surrogate bij position " + position);
        }

        position += 2;
        final var low = readHexQuad();

        if (!Character.isLowSurrogate(low)) {
            throw new IllegalArgumentException(
                    "High surrogate zonder low surrogate bij position " + position);
        }

        return new String(new char[]{high, low});
    }

    private char readHexQuad() {
        if (position + 4 > source.length()) {
            throw new IllegalArgumentException("Onvolledige \\u-escape bij position " + position);
        }

        var value = 0;

        for (var i = 0; i < 4; i++) {
            final var digit = Character.digit(source.charAt(position), 16);

            if (digit < 0) {
                throw new IllegalArgumentException(
                        "Ongeldige hex in \\u-escape bij position " + position);
            }

            value = value * 16 + digit;
            position++;
        }

        return (char) value;
    }

    /**
     * Leest een JSON-getal volgens de JSON-grammatica. Gehele getallen blijven
     * {@link Long} (een Double zou bij grote waarden precisie verliezen);
     * breuken en exponenten worden een Double.
     */
    private Object parseNumber() {
        final var start = position;

        if (!atEnd() && current() == '-') {
            position++;
        }

        readDigits("geheel getal");
        var isDecimal = false;

        if (!atEnd() && current() == '.') {
            position++;
            readDigits("fractie");
            isDecimal = true;
        }

        if (!atEnd() && (current() == 'e' || current() == 'E')) {
            position++;

            if (!atEnd() && (current() == '+' || current() == '-')) {
                position++;
            }

            readDigits("exponent");
            isDecimal = true;
        }

        final var text = source.substring(start, position);

        try {
            // Geen ternary: Long en Double zouden anders tot double worden
            // gepromoveerd en als Double worden teruggegeven.
            if (isDecimal) {
                return Double.valueOf(text);
            } else {
                return Long.valueOf(text);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Ongeldig getal: " + text, e);
        }
    }

    private void readDigits(final String what) {
        final var start = position;

        while (!atEnd() && current() >= '0' && current() <= '9') {
            position++;
        }

        if (position == start) {
            throw new IllegalArgumentException("Ongeldig getal: cijfers ontbreken in " + what
                    + " bij position " + position);
        }
    }

    private void expect(final char expected) {
        if (current() != expected) {
            throw new IllegalArgumentException("Verwacht '" + expected + "' bij position " + position
                    + ", gevonden '" + current() + "'");
        }
        position++;
    }

    private void expectWord(final String word) {
        for (final var c : word.toCharArray()) {
            if (atEnd() || current() != c) {
                throw new IllegalArgumentException("Verwacht '" + word + "'");
            }
            position++;
        }
    }

    private void skipWhitespace() {
        while (!atEnd() && Character.isWhitespace(current())) {
            position++;
        }
    }

    private char current() {
        return source.charAt(position);
    }

    private boolean atEnd() {
        return position >= source.length();
    }
}
