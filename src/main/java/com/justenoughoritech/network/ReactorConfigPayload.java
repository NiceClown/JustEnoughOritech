package com.justenoughoritech.network;

import com.justenoughoritech.JustEnoughOritech;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.ArrayList;
import java.util.List;

public record ReactorConfigPayload(String oritechVersion, boolean compatible,
                                   int maxSize, int rfPerPulse, int absorberRate,
                                   int ventBaseRate, int ventRelativeRate, int maxHeat,
                                   int maxUnstableTicks, int outputPerPort,
                                   boolean safeMode, int safeModeCooldown,
                                   List<FuelOption> fuels, List<String> coolants,
                                   String compatibilityIssue) implements CustomPacketPayload {
    static final int MAX_RESOURCES = 4096;
    static final int MAX_VERSION_LENGTH = 64;
    static final int MAX_STRING_LENGTH = 256;

    public record FuelOption(String itemId, int duration) {
        public FuelOption {
            itemId = itemId == null ? "" : itemId;
        }
    }

    public ReactorConfigPayload {
        oritechVersion = oritechVersion == null ? "unknown" : oritechVersion;
        fuels = fuels == null ? List.of() : List.copyOf(fuels);
        coolants = coolants == null ? List.of() : List.copyOf(coolants);
        compatibilityIssue = compatibilityIssue == null ? "" : compatibilityIssue;
    }
    public static final Type<ReactorConfigPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(JustEnoughOritech.MOD_ID, "reactor_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReactorConfigPayload> STREAM_CODEC =
            StreamCodec.of(ReactorConfigPayload::write, ReactorConfigPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, ReactorConfigPayload p) {
        int fuelCount = boundedCount(p.fuels().size());
        int coolantCount = boundedCount(p.coolants().size());
        buf.writeUtf(p.oritechVersion(), MAX_VERSION_LENGTH);buf.writeBoolean(p.compatible());
        buf.writeVarInt(p.maxSize());buf.writeVarInt(p.rfPerPulse());buf.writeVarInt(p.absorberRate());
        buf.writeVarInt(p.ventBaseRate());buf.writeVarInt(p.ventRelativeRate());buf.writeVarInt(p.maxHeat());
        buf.writeVarInt(p.maxUnstableTicks());buf.writeVarInt(p.outputPerPort());
        buf.writeBoolean(p.safeMode());buf.writeVarInt(p.safeModeCooldown());
        buf.writeVarInt(fuelCount);
        for(var fuel:p.fuels()){buf.writeUtf(fuel.itemId(), MAX_STRING_LENGTH);buf.writeVarInt(fuel.duration());}
        buf.writeVarInt(coolantCount);
        for(var coolant:p.coolants())buf.writeUtf(coolant, MAX_STRING_LENGTH);
        buf.writeUtf(p.compatibilityIssue(), MAX_STRING_LENGTH);
    }

    private static ReactorConfigPayload read(RegistryFriendlyByteBuf buf) {
        String version=buf.readUtf(MAX_VERSION_LENGTH);boolean compatible=buf.readBoolean();
        int maxSize=buf.readVarInt(),rfPerPulse=buf.readVarInt(),absorberRate=buf.readVarInt();
        int ventBaseRate=buf.readVarInt(),ventRelativeRate=buf.readVarInt(),maxHeat=buf.readVarInt();
        int maxUnstableTicks=buf.readVarInt(),output=buf.readVarInt();
        boolean safeMode=buf.readBoolean();int safeModeCooldown=buf.readVarInt();
        int fuelCount=boundedCount(buf.readVarInt());var fuels=new ArrayList<FuelOption>(fuelCount);
        for(int i=0;i<fuelCount;i++)fuels.add(new FuelOption(buf.readUtf(MAX_STRING_LENGTH),buf.readVarInt()));
        int coolantCount=boundedCount(buf.readVarInt());var coolants=new ArrayList<String>(coolantCount);
        for(int i=0;i<coolantCount;i++)coolants.add(buf.readUtf(MAX_STRING_LENGTH));
        String issue=buf.readUtf(MAX_STRING_LENGTH);
        return new ReactorConfigPayload(version,compatible,maxSize,rfPerPulse,absorberRate,
                ventBaseRate,ventRelativeRate,maxHeat,maxUnstableTicks,output,safeMode,
                safeModeCooldown,fuels,coolants,issue);
    }

    private static int boundedCount(int count) {
        if(count<0||count>MAX_RESOURCES)throw new IllegalArgumentException("Invalid reactor resource count: "+count);
        return count;
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleClient(ReactorConfigPayload payload, IPayloadContext context) {
        if (FMLEnvironment.getDist() == Dist.CLIENT)
            context.enqueueWork(() -> com.justenoughoritech.client.ClientRuntimeConfig.accept(payload));
    }
}
