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
                final var escape = source.charAt(position++);
                switch (escape) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    default -> sb.append(escape);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("Niet-afgesloten string");
    }

    private Double parseNumber() {
        final var start = position;
        while (!atEnd()) {
            final var c = current();
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                position++;
            } else {
                break;
            }
        }
        final var text = source.substring(start, position).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Ongeldig getal bij position " + position);
        }
        try {
            return Double.valueOf(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Ongeldig getal: " + text, e);
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
