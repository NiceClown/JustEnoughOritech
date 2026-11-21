package com.justenoughoritech.client;

import com.justenoughoritech.network.ReactorConfigPayload;

/**
 * Remembers the blueprint revision and server snapshot an auto-design result was computed from.
 * A result may only be applied while both still match, even after the risky-apply confirmation.
 */
final class DesignGuard {
    private final int revision;
    private final ReactorConfigPayload snapshot;

    private DesignGuard(int revision, ReactorConfigPayload snapshot) {
        this.revision = revision;
        this.snapshot = snapshot;
    }

    /** Capture the current blueprint revision and server snapshot. */
    static DesignGuard capture() {
        return new DesignGuard(Workbench.INSTANCE.revision(), ClientRuntimeConfig.snapshot());
    }

    boolean isCurrent() {
        return revision == Workbench.INSTANCE.revision() && snapshot == ClientRuntimeConfig.snapshot();
    }
}
