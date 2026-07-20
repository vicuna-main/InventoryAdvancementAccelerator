package dev.invadvopt.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class InvAdvOptConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.ConfigValue<String> MODE;
    public static final ModConfigSpec.DoubleValue SHADOW_VERIFY_RATE;
    public static final ModConfigSpec.IntValue PERIODIC_FULL_SCAN_TICKS;
    public static final ModConfigSpec.BooleanValue FALLBACK_ON_UNKNOWN_PREDICATE;
    public static final ModConfigSpec.BooleanValue FALLBACK_ON_OFF_THREAD_CALL;
    public static final ModConfigSpec.BooleanValue DISABLE_ON_MISMATCH;
    public static final ModConfigSpec.BooleanValue METRICS_ENABLED;
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Inventory Advancement Accelerator common configuration.");
        ENABLED = builder.comment("Master switch. False always preserves the vanilla trigger.")
                .define("enabled", true);
        MODE = builder.comment("EXACT preserves vanilla timing. AGGRESSIVE is experimental and may change trigger timing.")
                .define("mode", "EXACT", value -> value instanceof String string
                        && (string.equals("VANILLA") || string.equals("EXACT") || string.equals("AGGRESSIVE")));
        SHADOW_VERIFY_RATE = builder.comment("Fraction of optimized events compared with a complete listener scan.")
                .defineInRange("shadowVerifyRate", 0.01D, 0.0D, 1.0D);
        PERIODIC_FULL_SCAN_TICKS = builder.comment("Force verification after this many server ticks; 0 disables it.")
                .defineInRange("periodicFullScanTicks", 200, 0, 72000);
        FALLBACK_ON_UNKNOWN_PREDICATE = builder.comment("Use vanilla when a predicate cannot be proven index-safe.")
                .define("fallbackOnUnknownPredicate", true);
        FALLBACK_ON_OFF_THREAD_CALL = builder.comment("Use vanilla for calls outside the server thread.")
                .define("fallbackOnOffThreadCall", true);
        DISABLE_ON_MISMATCH = builder.comment("Disable a player's index after any shadow mismatch.")
                .define("disableOnMismatch", true);
        METRICS_ENABLED = builder.define("metricsEnabled", true);
        DEBUG_LOGGING = builder.define("debugLogging", false);
        SPEC = builder.build();
    }

    private InvAdvOptConfig() {}
}
