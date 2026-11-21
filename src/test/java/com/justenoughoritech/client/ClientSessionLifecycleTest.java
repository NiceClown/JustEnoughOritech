package com.justenoughoritech.client;

import com.justenoughoritech.network.ReactorConfigPayload;
import com.justenoughoritech.sim.ReactorConfig;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Client lifecycle: a new or lost server session must never leave stale config, results or simulation behind. */
class ClientSessionLifecycleTest {
    private static ReactorConfigPayload snapshot(String version, boolean compatible, String issue) {
        return new ReactorConfigPayload(version, compatible, 66, 128, 16, 4, 100, 2000, 200, 100,
                true, 100, List.of(new ReactorConfigPayload.FuelOption("minecraft:coal", 200)),
                List.of("minecraft:water"), issue);
    }

    @AfterEach
    void forgetServer() {
        ClientRuntimeConfig.reset();
    }

    @Test
    void disconnectForgetsTheServerSnapshot() {
        ClientRuntimeConfig.accept(snapshot("2.0.0-exp14", true, ""));
        assertEquals(ClientRuntimeConfig.Compatibility.VERIFIED, ClientRuntimeConfig.compatibility());

        ClientRuntimeConfig.reset();

        assertNull(ClientRuntimeConfig.snapshot(), "a disconnected client must not keep server data");
        assertEquals(ClientRuntimeConfig.Compatibility.WAITING, ClientRuntimeConfig.compatibility());
        assertEquals(ReactorConfig.DEFAULT, ClientRuntimeConfig.config());
        assertNull(ClientRuntimeConfig.runtimeIssue());
    }

    @Test
    void reloadReplacesTheSnapshotAndRearmsRuntimeVerification() {
        var first = snapshot("2.0.0-exp14", true, "");
        ClientRuntimeConfig.accept(first);
        ClientRuntimeConfig.setRuntimeCheck(false, "stale runtime verdict");
        assertEquals(ClientRuntimeConfig.Compatibility.INCOMPATIBLE, ClientRuntimeConfig.compatibility());

        var reloaded = snapshot("2.0.0-exp14", true, "");
        ClientRuntimeConfig.accept(reloaded);

        assertNotSame(first, ClientRuntimeConfig.snapshot(), "each payload is a fresh server snapshot");
        assertSame(reloaded, ClientRuntimeConfig.snapshot());
        assertNull(ClientRuntimeConfig.runtimeIssue(), "the old runtime verdict must not survive a reload");
        assertEquals(ClientRuntimeConfig.Compatibility.VERIFIED, ClientRuntimeConfig.compatibility());
    }

    @Test
    void unreachableServerYieldsIncompatibleAndNoConfig() {
        ClientRuntimeConfig.accept(snapshot("2.0.0-exp12", false, "gui.just_enough_oritech.no_fuel"));

        assertEquals(ClientRuntimeConfig.Compatibility.INCOMPATIBLE, ClientRuntimeConfig.compatibility());
        assertEquals("gui.just_enough_oritech.no_fuel", ClientRuntimeConfig.runtimeIssue());
        assertEquals(ReactorConfig.DEFAULT, ClientRuntimeConfig.config());
    }

    @Test
    void aServerSessionChangeDropsDerivedWorkbenchState() {
        var workbench = Workbench.INSTANCE;
        ClientRuntimeConfig.accept(snapshot("2.0.0-exp14", true, ""));
        workbench.designStatus = Component.literal("auto design result");
        workbench.issue = "gui.just_enough_oritech.missing_blocks";

        ClientRuntimeConfig.reset();

        assertNull(workbench.designStatus, "old design results must not be shown for a new session");
        assertNull(workbench.issue);
        assertTrue(workbench.simulation == null);
        assertFalse(workbench.designing());
        assertTrue(!workbench.inputs.available(), "no server means no fuels/coolants");
    }

    @Test
    void guardFollowsBlueprintEditsAndSnapshotReplacement() {
        var first = snapshot("2.0.0-exp14", true, "");
        ClientRuntimeConfig.accept(first);

        var guard = DesignGuard.capture();
        assertTrue(guard.isCurrent());

        Workbench.INSTANCE.edited();
        assertFalse(guard.isCurrent(), "editing the blueprint invalidates the result");

        var afterEdit = DesignGuard.capture();
        assertTrue(afterEdit.isCurrent());

        ClientRuntimeConfig.accept(snapshot("2.0.0-exp14", true, ""));
        assertFalse(afterEdit.isCurrent(), "a reload invalidates results computed against the old payload");

        var afterReload = DesignGuard.capture();
        assertTrue(afterReload.isCurrent());
        ClientRuntimeConfig.reset();
        assertFalse(afterReload.isCurrent(), "a disconnect invalidates every result");
    }
}
