package top.niunaijun.blackbox.utils;

public final class DiagnosticSwitch {
    private DiagnosticSwitch() {
    }

    public static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return isTruthyExact(normalized);
    }

    public static boolean isTruthyExact(String value) {
        return "1".equals(value)
                || "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value);
    }
}
