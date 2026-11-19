package com.justenoughoritech.sim;

import com.justenoughoritech.network.ReactorConfigPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Server-authoritative fuel recipes and coolant tag members from the login snapshot. */
public record ReactorInputs(List<Fuel> fuels, List<ItemStack> coolants, String error) {
    public record Fuel(ItemStack item, int duration) {}

    public ReactorInputs {
        fuels=List.copyOf(fuels);
        coolants=coolants.stream().map(ItemStack::copy).toList();
    }

    public boolean available(){return error==null&&!fuels.isEmpty()&&!coolants.isEmpty();}

    public static ReactorInputs fromServer(ReactorConfigPayload snapshot){
        if(snapshot==null)return failed("Waiting for server reactor data");
        var fuels=new ArrayList<Fuel>();
        for(var option:snapshot.fuels()){
            var id=parse(option.itemId());
            if(id==null||!BuiltInRegistries.ITEM.containsKey(id)||option.duration()<=0)continue;
            var stack=new ItemStack(BuiltInRegistries.ITEM.getValue(id));
            if(!stack.isEmpty())fuels.add(new Fuel(stack,option.duration()));
        }
        var coolants=new ArrayList<ItemStack>();
        for(String rawId:snapshot.coolants()){
            var id=parse(rawId);
            if(id==null||!BuiltInRegistries.ITEM.containsKey(id))continue;
            var stack=new ItemStack(BuiltInRegistries.ITEM.getValue(id));
            if(!stack.isEmpty())coolants.add(stack);
        }
        if(fuels.isEmpty())return failed("gui.just_enough_oritech.no_fuel");
        if(coolants.isEmpty())return failed("gui.just_enough_oritech.no_coolant");
        return new ReactorInputs(fuels,coolants,null);
    }

    private static Identifier parse(String value){
        int separator=value.indexOf(':');
        if(separator<1||separator==value.length()-1)return null;
        return Identifier.fromNamespaceAndPath(value.substring(0,separator),value.substring(separator+1));
    }
    private static ReactorInputs failed(String message){return new ReactorInputs(List.of(),List.of(),message);}
}
