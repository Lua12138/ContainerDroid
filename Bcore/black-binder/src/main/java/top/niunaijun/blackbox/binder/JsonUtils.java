package top.niunaijun.blackbox.binder;

final class JsonUtils {
    private JsonUtils() {
    }

    static void appendString(StringBuilder builder, String name, String value) {
        appendName(builder, name);
        if (value == null) {
            builder.append("null");
        } else {
            builder.append('"').append(escape(value)).append('"');
        }
    }

    static void appendLong(StringBuilder builder, String name, long value) {
        appendName(builder, name);
        builder.append(value);
    }

    static void appendInt(StringBuilder builder, String name, int value) {
        appendName(builder, name);
        builder.append(value);
    }

    static void appendBoolean(StringBuilder builder, String name, boolean value) {
        appendName(builder, name);
        builder.append(value);
    }

    static void appendName(StringBuilder builder, String name) {
        if (builder.charAt(builder.length() - 1) != '{' && builder.charAt(builder.length() - 1) != '[') {
            builder.append(',');
        }
        builder.append('"').append(name).append("\":");
    }

    static String escape(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                    break;
            }
        }
        return builder.toString();
    }
}
