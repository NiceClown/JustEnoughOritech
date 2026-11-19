package com.justenoughoritech.sim;

import org.joml.Vector2i;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Client-side simulation matching Oritech's sequential component update rules. */
public final class ReactorSimulation {
    public enum Status { READY, RUNNING, STEADY, MELTDOWN, SAFE_COOLDOWN, INCOMPATIBLE }

    public static final class Cell {
        public final int x, z;
        public final ReactorParts.Part part;
        public int heat, pulses;
        private int fuelRemaining, coolantRemaining;
        private Cell[] neighbors = new Cell[0];
        Cell(int x, int z, ReactorParts.Part part) { this.x=x; this.z=z; this.part=part; }
    }

    private static final int GRAPH_SIZE = 96;
    private static final int COOLANT_ITEM_CAPACITY = 1000;
    private final ReactorLayout layout;
    private final ReactorConfig config;
    private final ReactorInputs.Fuel fuel;
    private final net.minecraft.world.item.ItemStack coolant;
    private final Map<Vector2i, Cell> map = new HashMap<>();
    private final Cell[][] grid;
    private final Cell[] order;
    private final int[] heatHistory = new int[GRAPH_SIZE];
    private int historyCursor, historyLength;
    private int ticks, unstableTicks, maxHeatSeen, fuelItems, coolantItems, steadyTicks;
    private final int[] heatCheckpoint, coolantCheckpoint;
    private int checkpointTick, checkpointWindow=1, checkpointPeak, checkpointCoolantItems;
    private double steadyCoolantPerSecond;
    private long generationPerLayer;
    private long generation;
    private Status status = Status.READY;

    public ReactorSimulation(ReactorLayout layout, ReactorConfig config,
                             ReactorInputs.Fuel fuel, net.minecraft.world.item.ItemStack coolant) {
        this.layout = layout;
        this.config = config;
        this.fuel = fuel;
        this.coolant = coolant;
        grid = new Cell[layout.length()][layout.width()];
        for (int z=0; z<layout.length(); z++) for (int x=0; x<layout.width(); x++) {
            var part = layout.get(x,z);
            if (part.kind() == ReactorParts.Kind.EMPTY) continue;
            var cell = new Cell(x,z,part);
            grid[z][x] = cell;
            map.put(new Vector2i(x,z),cell);
        }
        order = map.values().toArray(Cell[]::new);
        heatCheckpoint=new int[order.length];
        coolantCheckpoint=map.values().stream().anyMatch(cell -> cell.part.kind()==ReactorParts.Kind.ABSORBER)
                ? new int[order.length] : null;
        for (Cell cell : order) {
            Set<Vector2i> keys = neighbors(new Vector2i(cell.x,cell.z),map.keySet());
            cell.neighbors = keys.stream().map(map::get).toArray(Cell[]::new);
            if (cell.part.kind() != ReactorParts.Kind.ROD) continue;
            cell.pulses = cell.part.pulses();
            for (Cell n : cell.neighbors) {
                if (n.part.kind() == ReactorParts.Kind.ROD) cell.pulses += n.part.rodCount();
                else if (n.part.kind() == ReactorParts.Kind.REFLECTOR) cell.pulses += cell.part.rodCount();
            }
            generationPerLayer += (long)config.rfPerPulse()*cell.pulses;
        }
    }

    private static Set<Vector2i> neighbors(Vector2i p, Set<Vector2i> positions) {
        var out = new HashSet<Vector2i>(4);
        Vector2i[] candidates = {new Vector2i(p).add(-1,0), new Vector2i(p).add(0,1),
                new Vector2i(p).add(1,0), new Vector2i(p).add(0,-1)};
        for (var candidate : candidates) if (positions.contains(candidate)) out.add(candidate);
        return out;
    }

    public void tick() {
        if (status != Status.RUNNING) return;
        int activeRods=0, hottest=0;
        generation=0;
        for (Cell c : order) {
            int heat=c.heat;
            switch (c.part.kind()) {
                case ROD -> {
                    int demand = c.part.rodCount() * layout.height();
                    if (c.fuelRemaining < demand) {
                        fuelItems++;
                        c.fuelRemaining = fuel.duration() - demand;
                    } else c.fuelRemaining -= demand;
                    activeRods++;
                    generation += (long)config.rfPerPulse()*c.pulses*layout.height();
                    heat += c.pulses/2*c.pulses+4;
                }
                case PIPE -> {
                    for (Cell n : c.neighbors) if (n.heat>heat) {
                        int diff=n.heat-heat;
                        int moved=Math.min(diff/4+10,diff);
                        n.heat-=moved; heat+=moved;
                    }
                }
                case ABSORBER -> {
                    if (c.coolantRemaining <= 0) {
                        c.coolantRemaining = COOLANT_ITEM_CAPACITY;
                        coolantItems++;
                    }
                    int removed=0;
                    if (c.coolantRemaining >= layout.height()) {
                        for (Cell n:c.neighbors) if(n.heat>0) {
                            n.heat-=config.absorberRate(); removed+=config.absorberRate();
                        }
                        if(removed>0)c.coolantRemaining-=layout.height();
                    } else if (c.coolantRemaining > 0) {
                        c.coolantRemaining=0;
                    }
                }
                case VENT -> {
                    Cell target=null; int max=0;
                    for(Cell n:c.neighbors) if(n.heat>max){target=n;max=n.heat;}
                    if(target!=null){int removed=Math.min(max/config.ventRelativeRate()+config.ventBaseRate(),max);target.heat-=removed;}
                }
                default -> { }
            }
            c.heat=heat;
            hottest=Math.max(hottest,heat);
        }
        ticks++;
        maxHeatSeen=Math.max(maxHeatSeen,hottest);
        heatHistory[historyCursor]=hottest;
        historyCursor=(historyCursor+1)%heatHistory.length;
        historyLength=Math.min(historyLength+1,heatHistory.length);

        if (hottest>config.maxHeat() && activeRods>0) {
            unstableTicks++;
            if (unstableTicks>config.maxUnstableTicks())
                status=config.safeMode()?Status.SAFE_COOLDOWN:Status.MELTDOWN;
        } else unstableTicks=0;

        if (activeRods==0) status=Status.STEADY;
        if (status==Status.RUNNING) checkStability(hottest);
    }

    private void checkStability(int hottest) {
        if (coolantCheckpoint==null) {
            steadyTicks=unstableTicks==0 && matchesCheckpoint() ? steadyTicks+1 : 0;
            if (steadyTicks>=200) status=Status.STEADY;
            else saveCheckpoint();
            return;
        }

        // Compare the entire thermal state, including coolant reserves, over increasing windows.
        // Equal states prove the same tick sequence will repeat under continuous supply.
        checkpointPeak=Math.max(checkpointPeak,hottest);
        int period=ticks-checkpointTick;
        if (checkpointPeak<=config.maxHeat() && matchesCheckpoint()) {
            steadyCoolantPerSecond=20.0*(coolantItems-checkpointCoolantItems)/period;
            status=Status.STEADY;
        } else if (period==checkpointWindow) {
            saveCheckpoint();
            checkpointWindow*=2;
        }
    }

    private boolean matchesCheckpoint() {
        for (int i=0;i<order.length;i++) {
            if (order[i].heat!=heatCheckpoint[i]
                    || coolantCheckpoint!=null && order[i].coolantRemaining!=coolantCheckpoint[i]) return false;
        }
        return true;
    }

    private void saveCheckpoint() {
        checkpointTick=ticks;
        checkpointCoolantItems=coolantItems;
        checkpointPeak=0;
        for (int i=0;i<order.length;i++) {
            heatCheckpoint[i]=order[i].heat;
            if (coolantCheckpoint!=null) coolantCheckpoint[i]=order[i].coolantRemaining;
        }
    }

    public void start(){ if(status==Status.READY) status=Status.RUNNING; }
    public void pause(){ if(status==Status.RUNNING) status=Status.READY; }
    public int ticks(){return ticks;}
    public int unstableTicks(){return unstableTicks;}
    public int ticksToMeltdown(){return Math.max(0,config.maxUnstableTicks()-unstableTicks+1);}
    public int maxHeatSeen(){return maxHeatSeen;}
    long generationPerLayer(){return generationPerLayer;}
    public long generation(){return generation;}
    public long exportRate(){return Math.min(generation,(long)layout.energyPorts()*config.maxEnergyOutputPerPort());}
    public int fuelItems(){return fuelItems;}
    public int coolantItems(){return coolantItems;}
    public double steadyCoolantPerSecond(){return steadyCoolantPerSecond;}
    boolean usesCoolant(){return coolantCheckpoint!=null;}
    static int coolantAlignedHeight(int height,int maxHeight) {
        for (int value=height;value<=maxHeight;value++) {
            if (COOLANT_ITEM_CAPACITY%value==0) return value;
        }
        return height;
    }
    public Status status(){return status;}
    public Cell cell(int x,int z){return grid[z][x];}
    public int[] snapshotHeat(){var a=new int[order.length];for(int i=0;i<order.length;i++)a[i]=order[i].heat;return a;}
    public int[] heatHistory(){
        var out=new int[historyLength];
        for(int i=0;i<historyLength;i++)out[i]=heatHistory[(historyCursor-historyLength+i+heatHistory.length)%heatHistory.length];
        return out;
    }
    public int totalCells(){return order.length;}
    public ReactorInputs.Fuel fuel(){return fuel;}
    public net.minecraft.world.item.ItemStack coolant(){return coolant;}
}
