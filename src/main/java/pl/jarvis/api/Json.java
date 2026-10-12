package pl.jarvis.api;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Json {
    private Json() { }

    static String string(String value) {
        if (value == null) return "null";
        return "\"" + escape(value) + "\"";
    }

    static String array(List<String> values) {
        return values.stream().map(Json::string).collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    static Optional<String> readString(String json, String field) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? Optional.of(unescape(matcher.group(1))) : Optional.empty();
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            char character = value.charAt(i);
            if (character == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                switch (next) {
                    case 'n' -> { result.append('\n'); i += 2; }
                    case 'r' -> { result.append('\r'); i += 2; }
                    case 't' -> { result.append('\t'); i += 2; }
                    case '"' -> { result.append('"'); i += 2; }
                    case '\\' -> { result.append('\\'); i += 2; }
                    case 'u' -> {
                        if (i + 5 < value.length()) {
                            try {
                                result.append((char) Integer.parseInt(value.substring(i + 2, i + 6), 16));
                                i += 6;
                            } catch (NumberFormatException e) {
                                result.append(next);
                                i += 2;
                            }
                        } else {
                            result.append(next);
                            i += 2;
                        }
                    }
                    default -> { result.append(next); i += 2; }
                }
            } else {
                result.append(character);
                i++;
            }
        }
        return result.toString();
    }
}
