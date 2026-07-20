package dev.invadvopt;

public enum OptimizationMode {
    VANILLA,
    EXACT,
    AGGRESSIVE;

    public static OptimizationMode parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException ignored) {
            return EXACT;
        }
    }
}
