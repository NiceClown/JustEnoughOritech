package com.justenoughoritech.client;

import com.justenoughoritech.network.ReactorConfigPayload;
import com.justenoughoritech.sim.ReactorConfig;

import java.util.Set;

public final class ClientRuntimeConfig {
    public enum Compatibility { WAITING, VERIFIED, UNVERIFIED, INCOMPATIBLE }
    private static final Set<String> VERIFIED_VERSIONS = Set.of("2.0.0-exp12", "2.0.0-exp14");
    private static volatile ReactorConfigPayload snapshot;
    private static volatile Boolean runtimeCompatible;
    private static volatile String runtimeIssue;
    private ClientRuntimeConfig() {}
    public static void accept(ReactorConfigPayload payload) { snapshot = payload; runtimeCompatible = null; runtimeIssue = null; }
    public static void setRuntimeCheck(boolean compatible, String issue) {
        runtimeCompatible = compatible;
        runtimeIssue = issue;
    }
    public static String runtimeIssue() {
        if(runtimeIssue!=null)return runtimeIssue;
        return snapshot==null||snapshot.compatibilityIssue().isBlank()?null:snapshot.compatibilityIssue();
    }
    public static ReactorConfigPayload snapshot() { return snapshot; }
    public static Compatibility compatibility() {
        if (snapshot == null) return Compatibility.WAITING;
        if (!snapshot.compatible() || Boolean.FALSE.equals(runtimeCompatible)) return Compatibility.INCOMPATIBLE;
        return VERIFIED_VERSIONS.contains(snapshot.oritechVersion()) ? Compatibility.VERIFIED : Compatibility.UNVERIFIED;
    }
    public static ReactorConfig config() {
        if (snapshot == null || !snapshot.compatible()) return ReactorConfig.DEFAULT;
        return new ReactorConfig(snapshot.maxSize(), snapshot.rfPerPulse(), snapshot.absorberRate(), snapshot.ventBaseRate(),
                snapshot.ventRelativeRate(), snapshot.maxHeat(), snapshot.maxUnstableTicks(),
                snapshot.outputPerPort(), snapshot.safeMode(), snapshot.safeModeCooldown());
    }
    public static String latestCompatible() { return "2.0.0-exp14"; }
}
