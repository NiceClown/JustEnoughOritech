package com.justenoughoritech.compat.jei;

import com.justenoughoritech.client.SimulatorWidget;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.List;

@JeiPlugin
public final class JEIPlugin implements IModPlugin {
    public static final IRecipeType<SimulatorRecipe> TYPE = IRecipeType.create(
            "just_enough_oritech", "reactor_simulator", SimulatorRecipe.class);

    private static mezz.jei.api.runtime.IJeiRuntime runtime;

    @Override public void onRuntimeAvailable(mezz.jei.api.runtime.IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    @Override public void onRuntimeUnavailable() {
        runtime = null;
    }

    public static void openSimulator() {
        if (runtime != null) runtime.getRecipesGui().showTypes(List.of(TYPE));
    }

    @Override public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath("just_enough_oritech", "jei_plugin");
    }

    @Override public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new SimulatorCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override public void registerRecipes(IRecipeRegistration registration) {
        registration.addRecipes(TYPE, List.of(new SimulatorRecipe()));
    }

    @Override public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        Block controller = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("oritech", "nuclear_reactor_controller"));
        if (controller != null && BuiltInRegistries.BLOCK.getKey(controller).getNamespace().equals("oritech"))
            registration.addCraftingStation(TYPE, new ItemStack(controller));
    }

    public record SimulatorRecipe() {}

    private static final class SimulatorCategory implements IRecipeCategory<SimulatorRecipe> {
        private final mezz.jei.api.gui.drawable.IDrawable icon;
        private final Block controller;
        SimulatorCategory(IGuiHelper helper) {
            Block controller = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("oritech", "nuclear_reactor_controller"));
            if (controller == null || BuiltInRegistries.BLOCK.getKey(controller) == null
                    || !BuiltInRegistries.BLOCK.getKey(controller).getNamespace().equals("oritech"))
                controller = net.minecraft.world.level.block.Blocks.FURNACE;
            icon = helper.createDrawableItemLike(controller);
            this.controller = controller;
        }
        @Override public IRecipeType<SimulatorRecipe> getRecipeType() { return TYPE; }
        @Override public Component getTitle() { return Component.translatable("jei.just_enough_oritech.reactor.category"); }
        @Override public int getWidth() { return SimulatorWidget.displayWidth(); }
        @Override public int getHeight() { return SimulatorWidget.displayHeight(); }
        @Override public mezz.jei.api.gui.drawable.IDrawable getIcon() { return icon; }
        @Override public void setRecipe(mezz.jei.api.gui.builder.IRecipeLayoutBuilder builder, SimulatorRecipe recipe, IFocusGroup focuses) {
            // Index this reference page for both recipe and usage searches on the controller.
            builder.addInvisibleIngredients(RecipeIngredientRole.INPUT).add(controller);
            builder.addInvisibleIngredients(RecipeIngredientRole.OUTPUT).add(controller);
        }
        @Override public void createRecipeExtras(mezz.jei.api.gui.widgets.IRecipeExtrasBuilder builder,
                                                  SimulatorRecipe recipe, IFocusGroup focuses) {
            builder.addWidget(new SimulatorWidget());
        }
    }
}
