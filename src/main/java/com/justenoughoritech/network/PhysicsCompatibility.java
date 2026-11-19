package com.justenoughoritech.network;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Fingerprints the exp12 classes that define reactor heat, pulses and resource timing. */
public final class PhysicsCompatibility {
    private static final Map<String,String> EXP12=Map.ofEntries(
            Map.entry("rearth/oritech/block/entity/reactor/NuclearReactorControllerBlockEntity.class","10ca6782cbb23c784f3b226b1f0f69ca8014c1743bede8eb38ddc438a06a9453"),
            Map.entry("rearth/oritech/block/entity/reactor/ReactorFuelPortEntity.class","f6a02c6290be4cf8982352af5f62a88aae0ee0ef780eb69380fc449fb341d3e6"),
            Map.entry("rearth/oritech/block/entity/reactor/ReactorCoolantAbsorberPortEntity.class","08bbbc1968429fb1deedd5a7a39f73b3fe6690da0bc5d9a4a32964b559f031d0"),
            Map.entry("rearth/oritech/block/blocks/reactor/BaseReactorBlock.class","5de79da0a4fd7cc1c95d85d18632fa0679a63a856165ba091d71cf024516a356"),
            Map.entry("rearth/oritech/block/blocks/reactor/NuclearReactorControllerBlock.class","4268ee83720e4217ab57173fafe8b7dfc5963cdf67da818a5eefef9aab176377"),
            Map.entry("rearth/oritech/block/blocks/reactor/ReactorRodBlock.class","3e2a32df314635f2e5b29296d7eba3892f0d5923f92a3e98685a8ce863d8d572"),
            Map.entry("rearth/oritech/block/blocks/reactor/ReactorAbsorberBlock.class","4d4130997555009bdadc3c6a93044a37b5046a129ebbed5a643486037d8d6735"),
            Map.entry("rearth/oritech/block/blocks/reactor/ReactorHeatPipeBlock.class","c4d96da08782351aae944ea24287e79799165a813f1a73298907f95dc3a766dc"),
            Map.entry("rearth/oritech/block/blocks/reactor/ReactorHeatVentBlock.class","b551c82e020fc9157dce35ddd40726d4ae43e17e363834fd60f5c35d6997f94a"),
            Map.entry("rearth/oritech/block/blocks/reactor/ReactorNeutronReflectorBlock.class","35ce19209a52e45eef7a9698fa0c559d9cc46d41d45100e6aa19b7d37499213d"),
            Map.entry("rearth/oritech/block/blocks/reactor/ReactorFuelPortBlock.class","7c90cb3220c810db57b42b8445d260dd65f36cadb500d83638526d283e8fab16"),
            Map.entry("rearth/oritech/block/blocks/reactor/ReactorCoolantAbsorberPortBlock.class","a805fa786acfe325cf15348038e2b4bd52f348f7005fe0b06bf70aa5fcde4454"),
            Map.entry("rearth/oritech/block/blocks/reactor/ReactorEnergyPortBlock.class","b10a0c5e3dadd4caf37ae2e4933dbb28a21237c4e57cfdd67499078481b0e706"),
            Map.entry("rearth/oritech/block/blocks/reactor/ReactorWallBlock.class","4ecd53646c30d94997f8e25c9e92951e5353037358d954a10b158e4ce13b60b0")
    );

    private PhysicsCompatibility(){}

    public static boolean matchesExp12(){
        try{
            ClassLoader loader=Class.forName("rearth.oritech.block.entity.reactor.NuclearReactorControllerBlockEntity",
                    false,Thread.currentThread().getContextClassLoader()).getClassLoader();
            MessageDigest sha=MessageDigest.getInstance("SHA-256");
            for(var entry:EXP12.entrySet()){
                try(InputStream stream=loader.getResourceAsStream(entry.getKey())){
                    if(stream==null)return false;
                    String actual=HexFormat.of().formatHex(sha.digest(stream.readAllBytes()));
                    if(!actual.equals(entry.getValue()))return false;
                }
            }
            return true;
        }catch(Exception exception){return false;}
    }
}
