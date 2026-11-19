package com.justenoughoritech.network;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;

/** Reads Oritech runtime config, server recipes and tags without linking to Oritech's Java API. */
public final class ServerConfigReader {
    private ServerConfigReader() {}

    public static ReactorConfigPayload read(MinecraftServer server) {
        String version=ModList.get().getModContainerById("oritech")
                .map(c->c.getModInfo().getVersion().toString()).orElse("unknown");
        try {
            Class<?> config=Class.forName("rearth.oritech.config.OritechConfig");
            int maxSize=integer(config,"maxSize"),rfPerPulse=integer(config,"rfPerPulse");
            int absorberRate=integer(config,"absorberRate"),ventBaseRate=integer(config,"ventBaseRate");
            int ventRelativeRate=integer(config,"ventRelativeRate"),maxHeat=integer(config,"maxHeat");
            int maxUnstableTicks=integer(config,"maxUnstableTicks");
            int output=integer(config,"reactorMaxEnergyOutput");
            boolean safeMode=bool(config,"safeMode");
            int safeModeCooldown=integer(config,"safeModeCooldown");
            var fuels=discoverFuels(server.getRecipeManager());
            var coolants=discoverCoolants();
            String issue=null;
            if(maxSize<=0||rfPerPulse<=0||absorberRate<=0||ventBaseRate<0||ventRelativeRate<=0
                    ||maxHeat<=0||maxUnstableTicks<0||output<=0||safeModeCooldown<0)
                issue="gui.just_enough_oritech.config_invalid";
            else if(fuels.isEmpty())issue="gui.just_enough_oritech.no_fuel";
            else if(coolants.isEmpty())issue="gui.just_enough_oritech.no_coolant";
            else if(!hasRequiredBlocks())issue="gui.just_enough_oritech.missing_blocks";
            else if(!PhysicsCompatibility.matchesExp12())issue="gui.just_enough_oritech.physics_changed";
            return new ReactorConfigPayload(version,issue==null,maxSize,rfPerPulse,absorberRate,
                    ventBaseRate,ventRelativeRate,maxHeat,maxUnstableTicks,output,safeMode,
                    safeModeCooldown,fuels,coolants,issue);
        } catch (ReflectiveOperationException|RuntimeException exception) {
            return new ReactorConfigPayload(version,false,0,0,0,0,0,0,0,0,false,0,
                    List.of(),List.of(),"gui.just_enough_oritech.missing_data");
        }
    }

    private static List<ReactorConfigPayload.FuelOption> discoverFuels(RecipeManager manager)
            throws ReflectiveOperationException {
        Class<?> content=Class.forName("rearth.oritech.init.recipes.RecipeContent");
        Object supplier=content.getField("REACTOR").get(null);
        Object rawType=((Supplier<?>)supplier).get();
        if(!(rawType instanceof RecipeType<?> reactorType))return List.of();
        var fuels=new LinkedHashMap<String,Integer>();
        for(RecipeHolder<?> holder:manager.getRecipes()){
            var recipe=holder.value();
            if(recipe.getType()!=reactorType)continue;
            Method inputsMethod=recipe.getClass().getMethod("itemInputs");
            Method durationMethod=recipe.getClass().getMethod("time");
            Method fluidMethod=recipe.getClass().getMethod("fluidInput");
            Object rawInputs=inputsMethod.invoke(recipe);
            Object rawFluid=fluidMethod.invoke(recipe);
            int duration=((Number)durationMethod.invoke(recipe)).intValue();
            if(!(rawInputs instanceof List<?> inputs)||inputs.size()!=1||duration<=0)continue;
            if(rawFluid instanceof java.util.Optional<?> optional&&optional.isPresent())continue;
            if(!(inputs.getFirst() instanceof Ingredient ingredient))continue;
            for(Holder<Item> item:ingredient.items().toList()){
                String id=BuiltInRegistries.ITEM.getKey(item.value()).toString();
                fuels.putIfAbsent(id,duration);
            }
        }
        var result=new ArrayList<ReactorConfigPayload.FuelOption>();
        fuels.forEach((id,duration)->result.add(new ReactorConfigPayload.FuelOption(id,duration)));
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static List<String> discoverCoolants() throws ReflectiveOperationException {
        Class<?> content=Class.forName("rearth.oritech.init.TagContent");
        Object rawTag=content.getField("REACTOR_COOLANT").get(null);
        if(!(rawTag instanceof TagKey<?>))return List.of();
        TagKey<Item> tag=(TagKey<Item>)rawTag;
        var coolants=new TreeSet<String>();
        for(Item item:BuiltInRegistries.ITEM){
            if(new ItemStack(item).is(tag))coolants.add(BuiltInRegistries.ITEM.getKey(item).toString());
        }
        return List.copyOf(coolants);
    }

    private static boolean hasRequiredBlocks(){
        for(String id:List.of("nuclear_reactor_controller","reactor_wall","reactor_fuel_port",
                "reactor_coolant_absorber_port","reactor_energy_port","reactor_rod",
                "reactor_heat_pipe","reactor_heat_absorber","reactor_heat_vent","reactor_neutron_reflector")){
            if(!BuiltInRegistries.BLOCK.containsKey(Identifier.fromNamespaceAndPath("oritech",id)))return false;
        }
        return true;
    }

    private static Object value(Class<?> type,String name)throws ReflectiveOperationException{
        Field field=type.getField(name);
        Object holder=field.get(null);
        return holder.getClass().getMethod("get").invoke(holder);
    }
    private static int integer(Class<?> type,String name)throws ReflectiveOperationException{
        return ((Number)value(type,name)).intValue();
    }
    private static boolean bool(Class<?> type,String name)throws ReflectiveOperationException{
        return (Boolean)value(type,name);
    }
}
