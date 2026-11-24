package com.justenoughoritech.printer;

import com.justenoughoritech.JustEnoughOritech;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Updates the custom blueprint name on an open {@link BlueprintBenchMenu}. */
public record UpdateBlueprintBenchNameRequest(String name) implements CustomPacketPayload {
    public UpdateBlueprintBenchNameRequest {
        name = BlueprintData.sanitizeName(name);
    }

    public static final Type<UpdateBlueprintBenchNameRequest> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(JustEnoughOritech.MOD_ID, "blueprint_bench_name"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateBlueprintBenchNameRequest> CODEC = StreamCodec.of(
            (buf, req) -> buf.writeUtf(req.name(), BlueprintData.MAX_NAME_LENGTH),
            buf -> new UpdateBlueprintBenchNameRequest(buf.readUtf(BlueprintData.MAX_NAME_LENGTH))
    );

    @Override
    public Type<UpdateBlueprintBenchNameRequest> type() {
        return TYPE;
    }

    public static void receive(UpdateBlueprintBenchNameRequest request, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player
                && player.containerMenu instanceof BlueprintBenchMenu menu) {
            menu.setBlueprintName(request.name());
        }
    }
}
