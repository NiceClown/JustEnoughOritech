package com.justenoughoritech.printer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;

import java.util.Objects;
import java.util.Optional;

public record BlueprintData(PrinterLayout layout, Optional<Anchor> anchor) {
    public BlueprintData {
        Objects.requireNonNull(layout, "layout");
        anchor = anchor == null ? Optional.empty() : anchor;
    }

    public static final Codec<BlueprintData> CODEC = RecordCodecBuilder.create(i -> i.group(
            PrinterLayout.CODEC.fieldOf("layout").forGetter(BlueprintData::layout),
            Anchor.CODEC.optionalFieldOf("anchor").forGetter(BlueprintData::anchor)
    ).apply(i, BlueprintData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlueprintData> STREAM_CODEC = StreamCodec.of(
            (buf, data) -> {
                data.layout().encode(buf);
                buf.writeBoolean(data.anchor().isPresent());
                data.anchor().ifPresent(anchor -> Anchor.STREAM_CODEC.encode(buf, anchor));
            },
            buf -> {
                var layout = PrinterLayout.decode(buf);
                Optional<Anchor> anchor = buf.readBoolean()
                        ? Optional.of(Anchor.STREAM_CODEC.decode(buf))
                        : Optional.empty();
                return new BlueprintData(layout, anchor);
            }
    );

    public static int rotationDegrees(Rotation rotation) {
        return switch (rotation == null ? Rotation.NONE : rotation) {
            case CLOCKWISE_90 -> 90;
            case CLOCKWISE_180 -> 180;
            case COUNTERCLOCKWISE_90 -> 270;
            case NONE -> 0;
        };
    }

    public record Anchor(Identifier dimension, BlockPos origin, Rotation rotation) {
        public Anchor {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(origin, "origin");
            rotation = rotation == null ? Rotation.NONE : rotation;
        }

        public static final Codec<Anchor> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("dimension").forGetter(Anchor::dimension),
                BlockPos.CODEC.fieldOf("origin").forGetter(Anchor::origin),
                Rotation.CODEC.fieldOf("rotation").forGetter(Anchor::rotation)
        ).apply(i, Anchor::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, Anchor> STREAM_CODEC = StreamCodec.of(
                (buf, anchor) -> {
                    Identifier.STREAM_CODEC.encode(buf, anchor.dimension());
                    buf.writeBlockPos(anchor.origin());
                    Rotation.STREAM_CODEC.encode(buf, anchor.rotation());
                },
                buf -> new Anchor(
                        Identifier.STREAM_CODEC.decode(buf),
                        buf.readBlockPos(),
                        Rotation.STREAM_CODEC.decode(buf)
                )
        );
    }
}
