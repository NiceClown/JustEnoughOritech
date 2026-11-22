package com.justenoughoritech.printer;

import com.justenoughoritech.sim.ReactorLayout;
import com.justenoughoritech.sim.ReactorParts;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public record PrinterLayout(int width, int length, int height, int ports, List<String> cells) {
    public static final int LIMIT = 66;
    public static final Codec<PrinterLayout> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(1,LIMIT).fieldOf("width").forGetter(PrinterLayout::width),
            Codec.intRange(1,LIMIT).fieldOf("length").forGetter(PrinterLayout::length),
            Codec.intRange(1,LIMIT).fieldOf("height").forGetter(PrinterLayout::height),
            Codec.intRange(1,ReactorLayout.maxEnergyPorts(LIMIT,LIMIT,LIMIT)).fieldOf("ports").forGetter(PrinterLayout::ports),
            Codec.STRING.listOf().fieldOf("cells").forGetter(PrinterLayout::cells)
    ).apply(i,PrinterLayout::new));

    public static PrinterLayout from(ReactorLayout layout) {
        var cells = new ArrayList<String>();
        for (int z=0;z<layout.length();z++)
            for (int x=0;x<layout.width();x++) cells.add(layout.get(x,z).id());
        return new PrinterLayout(layout.width(),layout.length(),layout.height(),layout.energyPorts(),List.copyOf(cells));
    }

    public ReactorLayout resolve(int maxSize) {
        var layout = resolveDraft(maxSize);
        return layout != null && layout.validationErrors(Math.min(maxSize,LIMIT)).isEmpty() ? layout : null;
    }

    public ReactorLayout resolveDraft(int maxSize) {
        int limit = Math.min(maxSize,LIMIT);
        if (width<1 || length<1 || height<1 || width>limit || length>limit || height>limit
                || ports<1 || ports>ReactorLayout.maxEnergyPorts(width,length,height)
                || cells.size()!=width*length) return null;
        var parts = ReactorParts.discover().stream().collect(Collectors.toMap(ReactorParts.Part::id,Function.identity()));
        var layout = new ReactorLayout();
        layout.resize(width,length,limit);
        layout.setHeight(height,limit);
        layout.setEnergyPorts(ports);
        for (int z=0;z<length;z++) for (int x=0;x<width;x++) {
            var part = parts.get(cells.get(z*width+x));
            if (part == null) return null;
            layout.set(x,z,part);
        }
        return layout;
    }

    public static PrinterLayout decode(RegistryFriendlyByteBuf buf) {
        int w = buf.readVarInt(), l = buf.readVarInt(), h = buf.readVarInt(), ports = buf.readVarInt();
        if (w<1 || w>LIMIT || l<1 || l>LIMIT || h<1 || h>LIMIT
                || ports<1 || ports>ReactorLayout.maxEnergyPorts(w,l,h))
            throw new IllegalArgumentException("Invalid printer dimensions");
        int size=buf.readVarInt();
        if (size<1 || size>32) throw new IllegalArgumentException("Invalid printer palette");
        var palette = new ArrayList<String>(size);
        for (int i=0;i<size;i++) palette.add(buf.readUtf(128));
        var cells = new ArrayList<String>(w*l);
        for (int i=0;i<w*l;i++) {
            int index=buf.readUnsignedByte();
            if (index>=size) throw new IllegalArgumentException("Invalid printer block index");
            cells.add(palette.get(index));
        }
        return new PrinterLayout(w,l,h,ports,List.copyOf(cells));
    }

    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(width);
        buf.writeVarInt(length);
        buf.writeVarInt(height);
        buf.writeVarInt(ports);
        var palette=new java.util.LinkedHashMap<String,Integer>();
        for (String cell : cells) palette.computeIfAbsent(cell,key -> palette.size());
        buf.writeVarInt(palette.size());
        for (String id : palette.keySet()) buf.writeUtf(id,128);
        for (String cell : cells) buf.writeByte(palette.get(cell));
    }
}
