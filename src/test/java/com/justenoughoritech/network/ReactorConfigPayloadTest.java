package com.justenoughoritech.network;

import com.justenoughoritech.client.ClientRuntimeConfig;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReactorConfigPayloadTest {

    @AfterEach
    void resetClientState() {
        ClientRuntimeConfig.reset();
    }

    private static RegistryFriendlyByteBuf newBuf() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }

    private static ReactorConfigPayload roundTrip(ReactorConfigPayload payload) {
        RegistryFriendlyByteBuf buf = newBuf();
        try {
            ReactorConfigPayload.STREAM_CODEC.encode(buf, payload);
            ReactorConfigPayload decoded = ReactorConfigPayload.STREAM_CODEC.decode(buf);
            assertEquals(0, buf.readableBytes(), "Codec should consume every written byte");
            return decoded;
        } finally {
            buf.release();
        }
    }

    @Test
    void roundTripsAtm11Exp12SnapshotExactly() {
        var payload = new ReactorConfigPayload(
                "2.0.0-exp12", true,
                64, 128, 16, 4, 100, 2000, 600, 25_000, false, 2400,
                List.of(
                        new ReactorConfigPayload.FuelOption("oritech:plutonium_pellet", 40_000),
                        new ReactorConfigPayload.FuelOption("oritech:small_plutonium_pellet", 4_000),
                        new ReactorConfigPayload.FuelOption("oritech:small_uranium_pellet", 400),
                        new ReactorConfigPayload.FuelOption("oritech:uranium_pellet", 4_000)
                ),
                List.of("minecraft:blue_ice", "minecraft:ice", "minecraft:packed_ice"),
                null
        );

        ReactorConfigPayload decoded = roundTrip(payload);
        assertEquals(payload, decoded);
        assertEquals("", decoded.compatibilityIssue());
        assertEquals(ReactorConfigPayload.TYPE, decoded.type());
        assertEquals("just_enough_oritech:reactor_config", ReactorConfigPayload.TYPE.id().toString());
    }

    @Test
    void roundTripsFtbEvolution2Exp14OverriddenSnapshotExactly() {
        var payload = new ReactorConfigPayload(
                "2.0.0-exp14", true,
                14, 38, 16, 4, 100, 4000, 400, 75_000, true, 0,
                List.of(new ReactorConfigPayload.FuelOption("oritech:uranium_pellet", 4_000)),
                List.of("minecraft:ice"),
                ""
        );

        ReactorConfigPayload decoded = roundTrip(payload);
        assertEquals(payload, decoded);
        assertTrue(decoded.safeMode());
        assertEquals(0, decoded.safeModeCooldown());
        assertEquals(14, decoded.maxSize());
        assertEquals(38, decoded.rfPerPulse());
        assertEquals(75_000, decoded.outputPerPort());
    }

    @Test
    void roundTripsIncompatibleSnapshotAndExtremeIntegers() {
        var payload = new ReactorConfigPayload(
                "2.0.0-exp99", false,
                0, Integer.MAX_VALUE, 1, 0, 1, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, true, Integer.MAX_VALUE,
                List.of(), List.of(), "gui.just_enough_oritech.physics_changed"
        );

        ReactorConfigPayload decoded = roundTrip(payload);
        assertEquals(payload, decoded);
        assertFalse(decoded.compatible());
        assertTrue(decoded.safeMode());
        assertEquals("gui.just_enough_oritech.physics_changed", decoded.compatibilityIssue());

        var bothFalse = new ReactorConfigPayload("unknown", false, 0, 0, 0, 0, 0, 0, 0, 0,
                false, 0, List.of(), List.of(), "gui.just_enough_oritech.missing_data");
        ReactorConfigPayload decodedFalse = roundTrip(bothFalse);
        assertEquals(bothFalse, decodedFalse);
        assertFalse(decodedFalse.compatible());
        assertFalse(decodedFalse.safeMode());
        assertEquals("gui.just_enough_oritech.missing_data", decodedFalse.compatibilityIssue());
    }

    @Test
    void compactConstructorsNormalizeNullsAndDefensivelyCopyLists() {
        var fuels = new ArrayList<ReactorConfigPayload.FuelOption>();
        fuels.add(new ReactorConfigPayload.FuelOption(null, 100));
        var coolants = new ArrayList<String>();
        coolants.add("minecraft:ice");

        var payload = new ReactorConfigPayload(null, true, 64, 128, 16, 4, 100, 2000, 600, 25_000,
                false, 2400, fuels, coolants, null);
        fuels.clear();
        coolants.clear();

        assertEquals("unknown", payload.oritechVersion());
        assertEquals("", payload.compatibilityIssue());
        assertEquals(1, payload.fuels().size());
        assertEquals("", payload.fuels().getFirst().itemId());
        assertEquals(1, payload.coolants().size());
        assertThrows(UnsupportedOperationException.class, () -> payload.fuels().add(
                new ReactorConfigPayload.FuelOption("oritech:uranium_pellet", 4000)));
        assertThrows(UnsupportedOperationException.class, () -> payload.coolants().add("minecraft:blue_ice"));

        var nullLists = new ReactorConfigPayload("2.0.0-exp14", false, 0, 0, 0, 0, 0, 0, 0, 0,
                false, 0, null, null, null);
        assertTrue(nullLists.fuels().isEmpty());
        assertTrue(nullLists.coolants().isEmpty());
        assertEquals(nullLists, roundTrip(nullLists));
    }

    @Test
    void exactMaxResourceCountRoundTrips() {
        var fuels = new ArrayList<ReactorConfigPayload.FuelOption>(ReactorConfigPayload.MAX_RESOURCES);
        var coolants = new ArrayList<String>(ReactorConfigPayload.MAX_RESOURCES);
        for (int i = 0; i < ReactorConfigPayload.MAX_RESOURCES; i++) {
            fuels.add(new ReactorConfigPayload.FuelOption("test:fuel_" + i, i + 1));
            coolants.add("test:coolant_" + i);
        }
        var payload = new ReactorConfigPayload("2.0.0-exp14", true, 64, 128, 16, 4, 100, 2000, 600,
                25_000, false, 2400, fuels, coolants, "");
        ReactorConfigPayload decoded = roundTrip(payload);
        assertEquals(ReactorConfigPayload.MAX_RESOURCES, decoded.fuels().size());
        assertEquals(ReactorConfigPayload.MAX_RESOURCES, decoded.coolants().size());
        assertEquals(payload, decoded);
    }

    @Test
    void exceedingMaxResourceCountRejectedOnEncode() {
        var tooManyFuels = new ArrayList<ReactorConfigPayload.FuelOption>(ReactorConfigPayload.MAX_RESOURCES + 1);
        for (int i = 0; i <= ReactorConfigPayload.MAX_RESOURCES; i++) {
            tooManyFuels.add(new ReactorConfigPayload.FuelOption("test:fuel_" + i, 100));
        }
        var badFuels = new ReactorConfigPayload("2.0.0-exp14", true, 64, 128, 16, 4, 100, 2000, 600,
                25_000, false, 2400, tooManyFuels, List.of("minecraft:ice"), "");
        RegistryFriendlyByteBuf buf1 = newBuf();
        try {
            assertThrows(IllegalArgumentException.class, () -> ReactorConfigPayload.STREAM_CODEC.encode(buf1, badFuels));
        } finally {
            buf1.release();
        }

        var tooManyCoolants = new ArrayList<String>(ReactorConfigPayload.MAX_RESOURCES + 1);
        for (int i = 0; i <= ReactorConfigPayload.MAX_RESOURCES; i++) {
            tooManyCoolants.add("test:coolant_" + i);
        }
        var badCoolants = new ReactorConfigPayload("2.0.0-exp14", true, 64, 128, 16, 4, 100, 2000, 600,
                25_000, false, 2400, List.of(new ReactorConfigPayload.FuelOption("test:fuel", 100)), tooManyCoolants, "");
        RegistryFriendlyByteBuf buf2 = newBuf();
        try {
            assertThrows(IllegalArgumentException.class, () -> ReactorConfigPayload.STREAM_CODEC.encode(buf2, badCoolants));
        } finally {
            buf2.release();
        }
    }

    @Test
    void malformedWireCountsRejectedOnDecode() {
        for (int badCount : new int[]{-1, ReactorConfigPayload.MAX_RESOURCES + 1, Integer.MIN_VALUE}) {
            RegistryFriendlyByteBuf buf = newBuf();
            try {
                writeHeader(buf);
                buf.writeVarInt(badCount);
                assertThrows(IllegalArgumentException.class, () -> ReactorConfigPayload.STREAM_CODEC.decode(buf),
                        "Fuel count should be rejected: " + badCount);
            } finally {
                buf.release();
            }

            RegistryFriendlyByteBuf buf2 = newBuf();
            try {
                writeHeader(buf2);
                buf2.writeVarInt(0); // fuels = 0
                buf2.writeVarInt(badCount); // bad coolants count
                assertThrows(IllegalArgumentException.class, () -> ReactorConfigPayload.STREAM_CODEC.decode(buf2),
                        "Coolant count should be rejected: " + badCount);
            } finally {
                buf2.release();
            }
        }
    }

    @Test
    void maxLengthStringsRoundTripAndOverlongStringsAreRejected() {
        String maxVersion = "v".repeat(ReactorConfigPayload.MAX_VERSION_LENGTH);
        String maxId = "m".repeat(ReactorConfigPayload.MAX_STRING_LENGTH);
        var valid = new ReactorConfigPayload(maxVersion, true, 64, 128, 16, 4, 100, 2000, 600,
                25_000, false, 2400,
                List.of(new ReactorConfigPayload.FuelOption(maxId, 400)),
                List.of(maxId), maxId);
        assertEquals(valid, roundTrip(valid));

        var tooLongVersion = new ReactorConfigPayload("v".repeat(ReactorConfigPayload.MAX_VERSION_LENGTH + 1),
                true, 64, 128, 16, 4, 100, 2000, 600, 25_000, false, 2400, List.of(), List.of(), "");
        RegistryFriendlyByteBuf buf = newBuf();
        try {
            assertThrows(EncoderException.class, () -> ReactorConfigPayload.STREAM_CODEC.encode(buf, tooLongVersion));
        } finally {
            buf.release();
        }
    }

    @Test
    void handleClientEnqueuesAndAcceptsSnapshotOnlyOnClientDist() {
        var payload = new ReactorConfigPayload("2.0.0-exp14", true, 14, 38, 16, 4, 100, 4000, 400,
                75_000, false, 2400,
                List.of(new ReactorConfigPayload.FuelOption("oritech:uranium_pellet", 4000)),
                List.of("minecraft:ice"), "");
        AtomicInteger enqueued = new AtomicInteger();
        IPayloadContext context = (IPayloadContext) Proxy.newProxyInstance(
                IPayloadContext.class.getClassLoader(),
                new Class<?>[]{IPayloadContext.class},
                (proxy, method, args) -> {
                    if ("enqueueWork".equals(method.getName()) && args != null && args.length == 1
                            && args[0] instanceof Runnable runnable) {
                        enqueued.incrementAndGet();
                        runnable.run();
                        return CompletableFuture.completedFuture(null);
                    }
                    throw new UnsupportedOperationException("Unexpected call: " + method.getName());
                }
        );

        ReactorConfigPayload.handleClient(payload, context, Dist.DEDICATED_SERVER);
        assertEquals(0, enqueued.get());
        assertNull(ClientRuntimeConfig.snapshot());

        ReactorConfigPayload.handleClient(payload, context, Dist.CLIENT);
        assertEquals(1, enqueued.get());
        assertEquals(payload, ClientRuntimeConfig.snapshot());
    }

    private static void writeHeader(RegistryFriendlyByteBuf buf) {
        buf.writeUtf("2.0.0-exp14", 64);
        buf.writeBoolean(true);
        buf.writeVarInt(64);
        buf.writeVarInt(128);
        buf.writeVarInt(16);
        buf.writeVarInt(4);
        buf.writeVarInt(100);
        buf.writeVarInt(2000);
        buf.writeVarInt(600);
        buf.writeVarInt(25_000);
        buf.writeBoolean(false);
        buf.writeVarInt(2400);
    }
}
