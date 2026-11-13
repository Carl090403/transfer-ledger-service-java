package dev.carlosf.ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, strict JSON reader/writer (RFC 8259 subset) so the service has zero runtime dependencies.
 * Numbers are returned as {@link BigDecimal} so the domain layer can reject them for money.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseException(String m) {
            super(m);
        }
    }

    public static Object parse(String text) {
        Json p = new Json(text == null ? "" : text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw new ParseException("trailing characters");
        return v;
    }

    private void ws() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
    }

    private char peek() {
        if (i >= s.length()) throw new ParseException("unexpected end of input");
        return s.charAt(i);
    }

    private Object value() {
        char c = peek();
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || Character.isDigit(c)) return number();
                throw new ParseException("unexpected character '" + c + "'");
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw new ParseException("invalid literal");
        i += word.length();
        return v;
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') throw new ParseException("expected key");
            String k = string();
            ws();
            if (peek() != ':') throw new ParseException("expected ':'");
            i++;
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            i++;
            if (c == '}') return m;
            if (c != ',') throw new ParseException("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return a;
        }
        while (true) {
            ws();
            a.add(value());
            ws();
            char c = peek();
            i++;
            if (c == ']') return a;
            if (c != ',') throw new ParseException("expected ',' or ']'");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            char c = peek();
            i++;
            if (c == '"') return b.toString();
            if (c < 0x20) throw new ParseException("control character in string");
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = peek();
            i++;
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw new ParseException("bad unicode escape");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw new ParseException("bad unicode escape");
                    }
                    i += 4;
                    break;
                default:
                    throw new ParseException("bad escape");
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (!n.matches("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")) throw new ParseException("invalid number");
        return new BigDecimal(n);
    }

    /** Serialises Map / List / String / Number / Boolean / null. */
    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(v, b);
        return b.toString();
    }

    private static void write(Object v, StringBuilder b) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            quote((String) v, b);
        } else if (v instanceof Number || v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), b);
                b.append(':');
                write(e.getValue(), b);
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) b.append(',');
                first = false;
                write(o, b);
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialise " + v.getClass());
        }
    }

    private static void quote(String str, StringBuilder b) {
        b.append('"');
        for (char c : str.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }
}
