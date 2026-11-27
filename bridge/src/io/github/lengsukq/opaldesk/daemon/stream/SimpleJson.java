package io.github.lengsukq.opaldesk.daemon.stream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal, zero-dependency, RFC 8259-compliant JSON builder and parser (supporting nested
 * objects and arrays) designed to run identically on Android ART and plain JVM unit tests
 * without relying on android.jar's stubbed org.json classes.
 */
public final class SimpleJson {
    private SimpleJson() {}

    public static final class ObjectBuilder {
        private final StringBuilder sb = new StringBuilder(128);
        private boolean first = true;

        public ObjectBuilder() {
            sb.append('{');
        }

        private void appendKey(String key) {
            if (!first) sb.append(',');
            first = false;
            appendEscapedString(sb, key);
            sb.append(':');
        }

        public ObjectBuilder put(String key, String value) {
            appendKey(key);
            if (value == null) {
                sb.append("null");
            } else {
                appendEscapedString(sb, value);
            }
            return this;
        }

        public ObjectBuilder put(String key, int value) {
            appendKey(key);
            sb.append(value);
            return this;
        }

        public ObjectBuilder put(String key, long value) {
            appendKey(key);
            sb.append(value);
            return this;
        }

        public ObjectBuilder put(String key, double value) {
            appendKey(key);
            if (!Double.isFinite(value)) {
                sb.append('0');
            } else if (value == Math.rint(value) && Math.abs(value) < 1e12) {
                sb.append(String.format(Locale.US, "%.1f", value));
            } else {
                sb.append(String.format(Locale.US, "%.2f", value));
            }
            return this;
        }

        public ObjectBuilder put(String key, boolean value) {
            appendKey(key);
            sb.append(value ? "true" : "false");
            return this;
        }

        public ObjectBuilder putRaw(String key, String rawJson) {
            appendKey(key);
            sb.append((rawJson != null && !rawJson.isEmpty()) ? rawJson : "null");
            return this;
        }

        public String build() {
            return sb.toString() + '}';
        }
    }

    public static final class ArrayBuilder {
        private final StringBuilder sb = new StringBuilder(64);
        private boolean first = true;

        public ArrayBuilder() {
            sb.append('[');
        }

        private void sep() {
            if (!first) sb.append(',');
            first = false;
        }

        public ArrayBuilder add(String value) {
            sep();
            if (value == null) {
                sb.append("null");
            } else {
                appendEscapedString(sb, value);
            }
            return this;
        }

        public ArrayBuilder add(int value) {
            sep();
            sb.append(value);
            return this;
        }

        public ArrayBuilder add(long value) {
            sep();
            sb.append(value);
            return this;
        }

        public ArrayBuilder add(boolean value) {
            sep();
            sb.append(value ? "true" : "false");
            return this;
        }

        public ArrayBuilder addRaw(String rawJson) {
            sep();
            sb.append((rawJson != null && !rawJson.isEmpty()) ? rawJson : "null");
            return this;
        }

        public String build() {
            return sb.toString() + ']';
        }
    }

    public static final class JsonObjectView {
        private final Map<String, Object> fields;

        JsonObjectView(Map<String, Object> fields) {
            this.fields = fields;
        }

        public boolean has(String key) {
            return fields.containsKey(key);
        }

        public String getString(String key, String def) {
            Object v = fields.get(key);
            if (v instanceof String) return (String) v;
            return def;
        }

        /**
         * Returns the integer value for {@code key}, coercing numeric strings and falling back to
         * {@code def} when absent or non-numeric.
         */
        public int getInt(String key, int def) {
            Object v = fields.get(key);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v instanceof String) {
                try {
                    return Integer.parseInt(((String) v).trim());
                } catch (NumberFormatException ignored) {
                    // Non-numeric string value falls back to caller's default.
                }
            }
            return def;
        }

        /**
         * Returns the long value for {@code key}, coercing numeric strings and falling back to
         * {@code def} when absent or non-numeric.
         */
        public long getLong(String key, long def) {
            Object v = fields.get(key);
            if (v instanceof Number) return ((Number) v).longValue();
            if (v instanceof String) {
                try {
                    return Long.parseLong(((String) v).trim());
                } catch (NumberFormatException ignored) {
                    // Non-numeric string value falls back to caller's default.
                }
            }
            return def;
        }

        /**
         * Returns the double value for {@code key}, coercing numeric strings and falling back to
         * {@code def} when absent or non-numeric.
         */
        public double getDouble(String key, double def) {
            Object v = fields.get(key);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof String) {
                try {
                    return Double.parseDouble(((String) v).trim());
                } catch (NumberFormatException ignored) {
                    // Non-numeric string value falls back to caller's default.
                }
            }
            return def;
        }

        public boolean getBoolean(String key, boolean def) {
            Object v = fields.get(key);
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof Number) return ((Number) v).intValue() != 0;
            if (v instanceof String) {
                String s = ((String) v).trim().toLowerCase(Locale.US);
                if ("true".equals(s) || "1".equals(s)) return true;
                if ("false".equals(s) || "0".equals(s)) return false;
            }
            return def;
        }

        public List<String> getStringList(String key) {
            Object v = fields.get(key);
            if (!(v instanceof List<?>)) return Collections.emptyList();
            List<?> raw = (List<?>) v;
            List<String> out = new ArrayList<>(raw.size());
            for (Object item : raw) {
                if (item instanceof String) {
                    out.add((String) item);
                } else if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }

        /**
         * Returns parsed integers from array {@code key}, skipping any non-numeric elements.
         */
        public List<Integer> getIntList(String key) {
            Object v = fields.get(key);
            if (!(v instanceof List<?>)) return Collections.emptyList();
            List<?> raw = (List<?>) v;
            List<Integer> out = new ArrayList<>(raw.size());
            for (Object item : raw) {
                if (item instanceof Number) {
                    out.add(((Number) item).intValue());
                } else if (item instanceof String) {
                    try {
                        out.add(Integer.parseInt(((String) item).trim()));
                    } catch (NumberFormatException ignored) {
                        // Skip malformed integer token in array.
                    }
                }
            }
            return out;
        }

        public JsonObjectView getObject(String key) {
            Object v = fields.get(key);
            return (v instanceof JsonObjectView) ? (JsonObjectView) v : null;
        }

        public List<JsonObjectView> getObjectList(String key) {
            Object v = fields.get(key);
            if (!(v instanceof List<?>)) return Collections.emptyList();
            List<?> raw = (List<?>) v;
            List<JsonObjectView> out = new ArrayList<>(raw.size());
            for (Object item : raw) {
                if (item instanceof JsonObjectView) {
                    out.add((JsonObjectView) item);
                }
            }
            return out;
        }
    }

    public static void appendEscapedString(StringBuilder out, String s) {
        out.append('"');
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\b': out.append("\\b"); break;
                    case '\f': out.append("\\f"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            out.append(String.format(Locale.US, "\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                        break;
                }
            }
        }
        out.append('"');
    }

    public static JsonObjectView parseObject(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON input is null");
        }
        Parser p = new Parser(json);
        p.skipWs();
        Map<String, Object> map = p.readObject();
        p.skipWs();
        if (!p.eof()) {
            throw new IllegalArgumentException("Unexpected trailing JSON data at offset " + p.pos);
        }
        return new JsonObjectView(map);
    }

    private static final class Parser {
        private final String s;
        private final int len;
        int pos = 0;

        Parser(String s) {
            this.s = s;
            this.len = s.length();
        }

        boolean eof() {
            return pos >= len;
        }

        void skipWs() {
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Map<String, Object> readObject() {
            return readObject(0);
        }

        private Map<String, Object> readObject(int depth) {
            if (depth > 16) {
                throw new IllegalArgumentException("JSON nesting too deep");
            }
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (!eof() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                skipWs();
                Object val = readValue(depth + 1);
                map.put(key, val);
                skipWs();
                if (eof()) {
                    throw new IllegalArgumentException("Unterminated JSON object");
                }
                char ch = s.charAt(pos++);
                if (ch == '}') {
                    return map;
                }
                if (ch != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}' at offset " + (pos - 1));
                }
            }
        }

        private Object readValue(int depth) {
            if (depth > 16) {
                throw new IllegalArgumentException("JSON nesting too deep");
            }
            if (eof()) {
                throw new IllegalArgumentException("Unexpected end of JSON value");
            }
            char c = s.charAt(pos);
            if (c == '"') {
                return readString();
            } else if (c == '{') {
                return new JsonObjectView(readObject(depth + 1));
            } else if (c == '[') {
                return readArray(depth + 1);
            } else if (c == 't') {
                expectLiteral("true");
                return Boolean.TRUE;
            } else if (c == 'f') {
                expectLiteral("false");
                return Boolean.FALSE;
            } else if (c == 'n') {
                expectLiteral("null");
                return null;
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                return readNumber();
            }
            throw new IllegalArgumentException("Unexpected JSON char '" + c + "' at offset " + pos);
        }

        private List<Object> readArray(int depth) {
            if (depth > 16) {
                throw new IllegalArgumentException("JSON nesting too deep");
            }
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (!eof() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(readValue(depth + 1));
                skipWs();
                if (eof()) throw new IllegalArgumentException("Unterminated nested array");
                char ch = s.charAt(pos++);
                if (ch == ']') return list;
                if (ch != ',') throw new IllegalArgumentException("Expected ',' or ']' in nested array");
            }
        }

        private String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (pos < len) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (pos >= len) {
                        throw new IllegalArgumentException("Unterminated escape sequence");
                    }
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"': out.append('"'); break;
                        case '\\': out.append('\\'); break;
                        case '/': out.append('/'); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'u':
                            if (pos + 4 > len) {
                                throw new IllegalArgumentException("Incomplete unicode escape");
                            }
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                out.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException nfe) {
                                throw new IllegalArgumentException("Invalid unicode escape: \\u" + hex);
                            }
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid escape char: \\" + esc);
                    }
                } else {
                    out.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private Number readNumber() {
            int start = pos;
            if (pos < len && s.charAt(pos) == '-') pos++;
            boolean isFloat = false;
            int digits = 0;
            while (pos < len && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
                digits++;
            }
            if (digits == 0) {
                throw new IllegalArgumentException("Invalid JSON number at offset " + start);
            }
            if (pos < len && s.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                int fracDigits = 0;
                while (pos < len && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                    pos++;
                    fracDigits++;
                }
                if (fracDigits == 0) {
                    throw new IllegalArgumentException("Invalid JSON fraction at offset " + start);
                }
            }
            if (pos < len && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < len && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                int expDigits = 0;
                while (pos < len && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                    pos++;
                    expDigits++;
                }
                if (expDigits == 0) {
                    throw new IllegalArgumentException("Invalid JSON exponent at offset " + start);
                }
            }
            String token = s.substring(start, pos);
            try {
                if (isFloat) {
                    return Double.valueOf(token);
                }
                return Long.valueOf(token);
            } catch (NumberFormatException nfe) {
                return Double.valueOf(token);
            }
        }

        private void expect(char expected) {
            if (eof() || s.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at offset " + pos);
            }
            pos++;
        }

        private void expectLiteral(String lit) {
            if (pos + lit.length() > len || !s.startsWith(lit, pos)) {
                throw new IllegalArgumentException("Expected literal '" + lit + "' at offset " + pos);
            }
            pos += lit.length();
        }
    }
}
