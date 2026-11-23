package com.justenoughoritech.network;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Fingerprints the reactor classes shared by Oritech 2.0.0-exp12 and 2.0.0-exp14 (heat, pulses and resource timing). */
public final class PhysicsCompatibility {
    static final String CONTROLLER_CLASS = "rearth.oritech.block.entity.reactor.NuclearReactorControllerBlockEntity";
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

    static Map<String,String> expectedFingerprints(){return EXP12;}

    public static boolean matchesExp12(){
        try{
            ClassLoader loader=resolveControllerLoader(
                    Thread.currentThread().getContextClassLoader(),
                    PhysicsCompatibility.class.getClassLoader());
            return matchesExp12(loader);
        }catch(Exception|LinkageError exception){return false;}
    }

    static boolean matchesExp12(ClassLoader loader){
        return matchesFingerprints(loader,EXP12);
    }

    static boolean matchesFingerprints(ClassLoader loader,Map<String,String> expected){
        if(loader==null||expected==null||expected.isEmpty())return false;
        try{
            MessageDigest sha=MessageDigest.getInstance("SHA-256");
            for(var entry:expected.entrySet()){
                try(InputStream stream=loader.getResourceAsStream(entry.getKey())){
                    if(stream==null)return false;
                    String actual=HexFormat.of().formatHex(sha.digest(stream.readAllBytes()));
                    if(!actual.equals(entry.getValue()))return false;
                }
            }
            return true;
        }catch(Exception|LinkageError exception){return false;}
    }

    static ClassLoader resolveControllerLoader(ClassLoader primary,ClassLoader fallback) throws ClassNotFoundException {
        if(primary!=null){
            try{
                return Class.forName(CONTROLLER_CLASS,false,primary).getClassLoader();
            }catch(ClassNotFoundException ignored){
                // Fall back to the mod classloader when invoked from a thread without NeoForge's TCCL.
            }
        }
        return Class.forName(CONTROLLER_CLASS,false,fallback).getClassLoader();
    }
}
