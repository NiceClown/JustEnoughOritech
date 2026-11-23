package com.justenoughoritech.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class PhysicsCompatibilityTest {
    private static final Path PRISM_INSTANCES = resolvePrismInstancesDir();
    private static final Path EXP12_JAR = PRISM_INSTANCES.resolve(
            Path.of("All the Mods 11 - ATM11(1)", "minecraft", "mods", "oritech-2.0.0-exp12.jar"));
    private static final Path EXP14_JAR = PRISM_INSTANCES.resolve(
            Path.of("FTB Evolution 2", "minecraft", "mods", "oritech-2.0.0-exp14.jar"));
    private static final Path LEGACY_1_2_13_JAR = PRISM_INSTANCES.resolve(
            Path.of("atm10aero+mymode", "minecraft", "mods", "oritech-neoforge-1.21.1-1.2.13.jar"));

    private static Path resolvePrismInstancesDir() {
        String override = System.getProperty("jeo.prism.instances");
        if (override != null && !override.isBlank()) return Path.of(override);
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) return Path.of(appData, "PrismLauncher", "instances");
        return Path.of(System.getProperty("user.home", "."), "AppData", "Roaming", "PrismLauncher", "instances");
    }

    @Test
    void expectedFingerprintsCoverAllFourteenReactorClassesWithValidSha256() {
        Map<String, String> fingerprints = PhysicsCompatibility.expectedFingerprints();
        assertEquals(14, fingerprints.size());
        assertTrue(fingerprints.containsKey("rearth/oritech/block/entity/reactor/NuclearReactorControllerBlockEntity.class"));
        assertTrue(fingerprints.containsKey("rearth/oritech/block/entity/reactor/ReactorFuelPortEntity.class"));
        assertTrue(fingerprints.containsKey("rearth/oritech/block/entity/reactor/ReactorCoolantAbsorberPortEntity.class"));
        assertTrue(fingerprints.containsKey("rearth/oritech/block/blocks/reactor/ReactorRodBlock.class"));
        assertTrue(fingerprints.containsKey("rearth/oritech/block/blocks/reactor/ReactorAbsorberBlock.class"));
        assertTrue(fingerprints.containsKey("rearth/oritech/block/blocks/reactor/ReactorHeatPipeBlock.class"));
        assertTrue(fingerprints.containsKey("rearth/oritech/block/blocks/reactor/ReactorHeatVentBlock.class"));
        assertTrue(fingerprints.containsKey("rearth/oritech/block/blocks/reactor/ReactorNeutronReflectorBlock.class"));
        for (var entry : fingerprints.entrySet()) {
            assertTrue(entry.getKey().endsWith(".class"), entry.getKey());
            assertTrue(entry.getValue().matches("[0-9a-f]{64}"), "Invalid SHA-256 hex for " + entry.getKey());
        }
    }

    @Test
    void nullOrEmptyClassLoaderReturnsFalse() {
        assertFalse(PhysicsCompatibility.matchesExp12(null));
        assertFalse(PhysicsCompatibility.matchesExp12(new MapClassLoader(Map.of())));
    }

    @Test
    void mismatchedBytesInSyntheticClassLoaderReturnFalse() {
        var fake = new HashMap<String, byte[]>();
        for (String path : PhysicsCompatibility.expectedFingerprints().keySet()) {
            fake.put(path, ("synthetic-" + path).getBytes());
        }
        assertFalse(PhysicsCompatibility.matchesExp12(new MapClassLoader(fake)));
    }

    @Test
    void ioFailureDuringStreamReadReturnsFalseInsteadOfThrowing() {
        ClassLoader broken = new ClassLoader(null) {
            @Override public InputStream getResourceAsStream(String name) {
                return new InputStream() {
                    @Override public int read() throws IOException {
                        throw new IOException("Simulated corrupt jar entry for " + name);
                    }
                };
            }
        };
        assertFalse(PhysicsCompatibility.matchesExp12(broken));
    }

    @Test
    void runtimeExceptionOrLinkageErrorDuringLookupReturnsFalse() {
        ClassLoader throwing = new ClassLoader(null) {
            @Override public InputStream getResourceAsStream(String name) {
                throw new IllegalStateException("closed zip");
            }
        };
        assertFalse(PhysicsCompatibility.matchesExp12(throwing));

        ClassLoader linkageFailing = new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) {
                throw new UnsupportedClassVersionError("Simulated future bytecode version");
            }
        };
        withTccl(linkageFailing, () -> assertFalse(PhysicsCompatibility.matchesExp12()));
    }

    @Test
    void matchesExp12WithNullTcclAndNoOritechOnClasspathReturnsFalse() {
        withTccl(null, () -> assertFalse(PhysicsCompatibility.matchesExp12()));
    }

    @Test
    void matchesExp12WithSyntheticStubControllerAndWrongBytesReturnsFalse() {
        var fake = new HashMap<String, byte[]>();
        for (String path : PhysicsCompatibility.expectedFingerprints().keySet()) {
            fake.put(path, new byte[]{1, 2, 3, 4});
        }
        ClassLoader loader = new StubControllerClassLoader(fake);
        withTccl(loader, () -> assertFalse(PhysicsCompatibility.matchesExp12()));
    }

    @Test
    void selfContainedSyntheticFingerprintsVerifyMatchTamperMissingAndEmptyGuards() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        var resources = new LinkedHashMap<String, byte[]>();
        var expected = new LinkedHashMap<String, String>();
        for (int i = 0; i < 4; i++) {
            String path = "rearth/oritech/synthetic/ReactorPart" + i + ".class";
            byte[] payload = ("reactor-bytecode-v2-" + i).getBytes(StandardCharsets.UTF_8);
            resources.put(path, payload);
            expected.put(path, HexFormat.of().formatHex(sha.digest(payload)));
        }

        // Exact match returns true on any machine without external JARs
        assertTrue(PhysicsCompatibility.matchesFingerprints(new MapClassLoader(resources), expected));

        // Null/empty expected map or null loader returns false
        assertFalse(PhysicsCompatibility.matchesFingerprints(null, expected));
        assertFalse(PhysicsCompatibility.matchesFingerprints(new MapClassLoader(resources), null));
        assertFalse(PhysicsCompatibility.matchesFingerprints(new MapClassLoader(resources), Map.of()));

        // Every single entry is enforced against 1-bit tamper, length change, and omission
        for (String path : expected.keySet()) {
            var missing = new HashMap<>(resources);
            missing.remove(path);
            assertFalse(PhysicsCompatibility.matchesFingerprints(new MapClassLoader(missing), expected),
                    "Missing entry must fail: " + path);

            var flipped = new HashMap<>(resources);
            byte[] mutated = flipped.get(path).clone();
            mutated[mutated.length - 1] ^= 0x01;
            flipped.put(path, mutated);
            assertFalse(PhysicsCompatibility.matchesFingerprints(new MapClassLoader(flipped), expected),
                    "1-bit tamper must fail: " + path);

            var extended = new HashMap<>(resources);
            byte[] longer = new byte[resources.get(path).length + 1];
            System.arraycopy(resources.get(path), 0, longer, 0, resources.get(path).length);
            extended.put(path, longer);
            assertFalse(PhysicsCompatibility.matchesFingerprints(new MapClassLoader(extended), expected),
                    "Trailing byte must fail: " + path);
        }
    }

    @Test
    void resolveControllerLoaderFallsBackWhenTcclMissesOrIsNull() throws Exception {
        ClassLoader empty = new MapClassLoader(Map.of());
        ClassLoader primaryHit = new StubControllerClassLoader(Map.of());
        ClassLoader fallbackHit = new StubControllerClassLoader(Map.of());

        assertSame(primaryHit, PhysicsCompatibility.resolveControllerLoader(primaryHit, empty));
        assertSame(fallbackHit, PhysicsCompatibility.resolveControllerLoader(empty, fallbackHit));
        assertSame(fallbackHit, PhysicsCompatibility.resolveControllerLoader(null, fallbackHit));
        assertThrows(ClassNotFoundException.class,
                () -> PhysicsCompatibility.resolveControllerLoader(empty, empty));
    }

    @Test
    void realExp12JarPassesDirectAndPublicMatchesExp12() throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(EXP12_JAR), "ATM11 oritech-2.0.0-exp12.jar not present");
        Map<String, byte[]> classes = readWatchedClasses(EXP12_JAR);
        assertEquals(14, classes.size());
        assertTrue(PhysicsCompatibility.matchesExp12(new MapClassLoader(classes)));
        withTccl(new StubControllerClassLoader(classes), () -> assertTrue(PhysicsCompatibility.matchesExp12()));
    }

    @Test
    void realExp14JarPassesAndEverySingleWatchedClassIsEnforced() throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(EXP14_JAR), "FTB Evo 2 oritech-2.0.0-exp14.jar not present");
        Map<String, byte[]> classes = readWatchedClasses(EXP14_JAR);
        assertEquals(14, classes.size());
        assertTrue(PhysicsCompatibility.matchesExp12(new MapClassLoader(classes)));
        withTccl(new StubControllerClassLoader(classes), () -> assertTrue(PhysicsCompatibility.matchesExp12()));

        // Flipping a single bit or removing ANY of the 14 classes must fail fingerprint verification.
        for (String path : PhysicsCompatibility.expectedFingerprints().keySet()) {
            var missingOne = new HashMap<>(classes);
            missingOne.remove(path);
            assertFalse(PhysicsCompatibility.matchesExp12(new MapClassLoader(missingOne)),
                    "Missing class should fail: " + path);

            var mutatedOne = new HashMap<>(classes);
            byte[] copy = mutatedOne.get(path).clone();
            copy[copy.length - 1] ^= 0x01;
            mutatedOne.put(path, copy);
            assertFalse(PhysicsCompatibility.matchesExp12(new MapClassLoader(mutatedOne)),
                    "Single-bit mutation should fail: " + path);
        }
    }

    @Test
    void realLegacy1213JarIsRejected() throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(LEGACY_1_2_13_JAR), "Legacy 1.2.13 jar not present");
        Map<String, byte[]> classes = readWatchedClasses(LEGACY_1_2_13_JAR);
        assertTrue(classes.size() < 14, "Legacy 1.2.13 uses renamed reactor classes");
        assertFalse(PhysicsCompatibility.matchesExp12(new MapClassLoader(classes)));
    }

    private static Map<String, byte[]> readWatchedClasses(Path jarPath) throws IOException {
        var result = new HashMap<String, byte[]>();
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            for (String entryName : PhysicsCompatibility.expectedFingerprints().keySet()) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    result.put(entryName, in.readAllBytes());
                }
            }
        }
        return result;
    }

    private static void withTccl(ClassLoader loader, Runnable action) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            action.run();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static class MapClassLoader extends ClassLoader {
        private final Map<String, byte[]> resources;

        MapClassLoader(Map<String, byte[]> resources) {
            super(null);
            this.resources = resources;
        }

        @Override public InputStream getResourceAsStream(String name) {
            byte[] data = resources.get(name);
            return data == null ? null : new ByteArrayInputStream(data);
        }
    }

    private static final class StubControllerClassLoader extends MapClassLoader {
        private static final byte[] STUB_CONTROLLER = ClassFile.of().build(
                ClassDesc.of(PhysicsCompatibility.CONTROLLER_CLASS), cb -> {});

        StubControllerClassLoader(Map<String, byte[]> resources) {
            super(resources);
        }

        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (PhysicsCompatibility.CONTROLLER_CLASS.equals(name)) {
                return defineClass(name, STUB_CONTROLLER, 0, STUB_CONTROLLER.length);
            }
            throw new ClassNotFoundException(name);
        }
    }
}
