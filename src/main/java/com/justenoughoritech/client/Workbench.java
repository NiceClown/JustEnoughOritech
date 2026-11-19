package com.justenoughoritech.client;

import com.justenoughoritech.sim.ReactorLayout;
import com.justenoughoritech.sim.ReactorParts;
import com.justenoughoritech.sim.ReactorInputs;
import com.justenoughoritech.sim.ReactorSimulation;
import com.justenoughoritech.sim.ReactorDesigner;

import java.util.List;

public final class Workbench {
    public static final Workbench INSTANCE = new Workbench();
    public final ReactorLayout layout = new ReactorLayout();
    public List<ReactorParts.Part> parts = List.of(ReactorParts.Part.EMPTY);
    public ReactorInputs inputs = new ReactorInputs(List.of(), List.of(), "Waiting for server recipes");
    public int selectedPart;
    public int selectedFuel;
    public int selectedCoolant;
    public int selectedLayer;
    public int gridOffsetX, gridOffsetZ, blockOffset;
    public int cursorX, cursorZ;
    public boolean layersMode, running, blocksMode;
    public ReactorSimulation simulation;
    public String issue;
    public int speed = 1;
    private boolean partsLoaded;
    private int revision;
    private boolean designing;
    public ReactorDesigner.Request lastRequest;
    public net.minecraft.network.chat.Component designStatus;
    public int revision() { return revision; }
    public boolean designing() { return designing; }
    private com.justenoughoritech.network.ReactorConfigPayload inputsSnapshot;
    private Workbench() {}

    public void refreshParts() {
        if(partsLoaded)return;
        var found = ReactorParts.discover();
        if (found.size() > 1) { parts = found; partsLoaded=true; }
    }
    public void refreshInputs() {
        refreshInputs(false);
    }
    public void refreshInputs(boolean force) {
        var snapshot=ClientRuntimeConfig.snapshot();
        if(snapshot==null)return;
        if(!force&&snapshot.equals(inputsSnapshot))return;
        inputsSnapshot=snapshot;
        if (!snapshot.compatible()) {
            issue = snapshot.compatibilityIssue();
            ClientRuntimeConfig.setRuntimeCheck(false, issue);
            return;
        }
        inputs = ReactorInputs.fromServer(snapshot);
        if (inputs.error()!=null && !inputs.error().equals("Waiting for server recipes")) {
            issue=inputs.error();
            ClientRuntimeConfig.setRuntimeCheck(false,issue);
            return;
        }
        var controller=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("oritech","nuclear_reactor_controller"));
        var controllerId=net.minecraft.resources.Identifier.fromNamespaceAndPath("oritech","nuclear_reactor_controller");
        if (controller==null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(controllerId)
                || ReactorParts.wall().block()==null || ReactorParts.fuelPort().block()==null
                || ReactorParts.coolantPort().block()==null || ReactorParts.energyPort().block()==null) {
            issue="gui.just_enough_oritech.missing_blocks";
            ClientRuntimeConfig.setRuntimeCheck(false,issue);
            return;
        }
        if (inputs.available()) {
            issue=null;
            ClientRuntimeConfig.setRuntimeCheck(true,null);
        }
    }
    public ReactorParts.Part selected() {
        return parts.get(Math.floorMod(selectedPart, parts.size()));
    }
    public void cyclePart(int delta) { selectedPart = Math.floorMod(selectedPart + delta, parts.size()); }
    public void start() {
        if (ClientRuntimeConfig.compatibility() == ClientRuntimeConfig.Compatibility.WAITING) return;
        refreshParts();
        refreshInputs(true);
        if (ClientRuntimeConfig.compatibility() == ClientRuntimeConfig.Compatibility.INCOMPATIBLE
                || !inputs.available()) return;
        if (simulation!=null) {
            if(simulation.status()==ReactorSimulation.Status.READY) {
                simulation.start();running=true;issue=null;return;
            }
            setIssue("gui.just_enough_oritech.restart");
            return;
        }
        var errors=layout.validationErrors(ClientRuntimeConfig.config().maxSize());
        if (!errors.isEmpty()) { setIssue(errors.get(0)); return; }
        var fuel=inputs.fuels().get(Math.floorMod(selectedFuel,inputs.fuels().size()));
        var coolant=inputs.coolants().get(Math.floorMod(selectedCoolant,inputs.coolants().size()));
        issue=null;
        simulation = new ReactorSimulation(layout, ClientRuntimeConfig.config(),fuel,coolant);
        simulation.start();
        running = true;
    }
    private void setIssue(String message){
        issue=message;
        var gui=net.minecraft.client.Minecraft.getInstance().gui;
        if(gui!=null)gui.setOverlayMessage(net.minecraft.network.chat.Component.translatable(message),false);
    }
    public void pause() { running = false; if (simulation != null) simulation.pause(); }
    public void reset() {
        running = false;
        simulation = null;
        issue = null;
    }

    public void edited() {
        reset();
        revision++;
        designStatus = null;
        gridOffsetX = Math.min(gridOffsetX, Math.max(0, layout.width() - 5));
        gridOffsetZ = Math.min(gridOffsetZ, Math.max(0, layout.length() - 5));
        selectedLayer = Math.min(selectedLayer, layout.height() - 1);
    }

    public void autoDesign(ReactorDesigner.Request request,
                           java.util.function.Consumer<ReactorDesigner.Result> onResult) {
        refreshParts();
        refreshInputs();
        if (designing || !inputs.available()
                || ClientRuntimeConfig.compatibility()==ClientRuntimeConfig.Compatibility.INCOMPATIBLE) {
            onResult.accept(null);
            return;
        }
        var client=net.minecraft.client.Minecraft.getInstance();
        var snapshot=ClientRuntimeConfig.snapshot();
        var config=ClientRuntimeConfig.config();
        var size=layout.copy();
        var partsForSearch=parts;
        var fuel=inputs.fuels().get(Math.floorMod(selectedFuel,inputs.fuels().size()));
        var coolant=inputs.coolants().get(Math.floorMod(selectedCoolant,inputs.coolants().size()));
        int expectedRevision=revision;
        lastRequest=request;
        designing=true;
        java.util.concurrent.CompletableFuture.supplyAsync(() ->
                ReactorDesigner.find(size,config,partsForSearch,fuel,coolant,request))
                .whenComplete((result,error) -> client.execute(() -> {
                    designing=false;
                    onResult.accept(error==null && revision==expectedRevision && ClientRuntimeConfig.snapshot()==snapshot
                            ? result : null);
                }));
    }

    public void applyDesign(ReactorDesigner.Design design,long target) {
        layout.replaceWith(design.layout());
        edited();
        if (design.dangerous()) {
            designStatus=SimulatorWidget.tr("auto_risky_applied",design.exportRate());
        } else if (target>0 && design.exportRate()<target) {
            designStatus=SimulatorWidget.tr("auto_goal_missed",design.exportRate(),target);
        } else {
            designStatus=SimulatorWidget.tr("auto_found",design.exportRate());
        }
    }

    public void tick() {
        if (!running || simulation == null) return;
        for (int i=0;i<speed;i++) simulation.tick();
        if (simulation.status()!=ReactorSimulation.Status.RUNNING) running=false;
    }
}
