package com.justenoughoritech.sim;

public record ReactorConfig(int maxSize, int rfPerPulse, int absorberRate, int ventBaseRate,
                            int ventRelativeRate, int maxHeat, int maxUnstableTicks,
                            int maxEnergyOutputPerPort, boolean safeMode, int safeModeCooldown) {
    public static final ReactorConfig DEFAULT = new ReactorConfig(66, 128, 16, 4, 100,
            2000, 600, 25_000, false, 2400);
}
