package com.justenoughoritech.sim;

import org.joml.Vector2i;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Client-side simulation matching Oritech's sequential component update rules. */
public final class ReactorSimulation {
    public enum Status { READY, RUNNING, STEADY, MELTDOWN, SAFE_COOLDOWN, INCOMPATIBLE }

    public static final class Cell {
        public final int x, z;
        public final ReactorParts.Part part;
        public int heat, pulses, lastHeatDelta, lastCoolingWork;
        private int heatBefore;
        private int fuelRemaining, coolantRemaining;
        private Cell[] neighbors = new Cell[0];
        Cell(int x, int z, ReactorParts.Part part) { this.x=x; this.z=z; this.part=part; }
        public int fuelRemaining() { return fuelRemaining; }
        public int coolantRemaining() { return coolantRemaining; }
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
    private int ticks, unstableTicks, maxHeatSeen, fuelItems, coolantItems;
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
            c.heatBefore = c.heat;
            c.lastCoolingWork = 0;
        }
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
                        c.lastCoolingWork += moved;
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
                            int actual = Math.min(n.heat, config.absorberRate());
                            n.heat-=config.absorberRate(); removed+=config.absorberRate();
                            c.lastCoolingWork += actual;
                        }
                        if(removed>0)c.coolantRemaining-=layout.height();
                    } else if (c.coolantRemaining > 0) {
                        c.coolantRemaining=0;
                    }
                }
                case VENT -> {
                    Cell target=null; int max=0;
                    for(Cell n:c.neighbors) if(n.heat>max){target=n;max=n.heat;}
                    if(target!=null){
                        int rel=Math.max(1,config.ventRelativeRate());
                        int removed=Math.clamp(max/rel+config.ventBaseRate(),0,max);
                        target.heat-=removed;
                        c.lastCoolingWork = removed;
                    }
                }
                default -> { }
            }
            c.heat=heat;
            hottest=Math.max(hottest,heat);
        }
        for (Cell c : order) c.lastHeatDelta = c.heat - c.heatBefore;
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
        // Compare the entire thermal state, including coolant reserves when present, over increasing
        // windows. Equal states prove the same tick sequence will repeat under continuous supply.
        // Passive reactors use the same detector: a fixed point is found in 1-2 ticks and a short
        // oscillation in a few ticks, instead of always waiting for 200 stable ticks.
        checkpointPeak=Math.max(checkpointPeak,hottest);
        int period=ticks-checkpointTick;
        if (checkpointPeak<=config.maxHeat() && matchesCheckpoint()) {
            steadyCoolantPerSecond=coolantCheckpoint==null ? 0.0
                    : 20.0*(coolantItems-checkpointCoolantItems)/period;
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
        if (height<=0) return height;
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

    public long cellGeneration(Cell cell) {
        if (cell == null || cell.part.kind() != ReactorParts.Kind.ROD) return 0L;
        return (long) config.rfPerPulse() * cell.pulses * layout.height();
    }

    public int cellHeatGeneration(Cell cell) {
        if (cell == null || cell.part.kind() != ReactorParts.Kind.ROD) return 0;
        return cell.pulses / 2 * cell.pulses + 4;
    }

    public double fuelPerSecond() {
        if (fuel == null || fuel.duration() <= 0) return 0.0;
        double rate = 0.0;
        for (Cell c : order) {
            if (c.part.kind() == ReactorParts.Kind.ROD) {
                rate += (double) c.part.rodCount() * layout.height() * 20.0 / fuel.duration();
            }
        }
        return rate;
    }

    public double fuelPerMinute() { return fuelPerSecond() * 60.0; }
    public double fuelPerHour() { return fuelPerSecond() * 3600.0; }
    public double fuelStackRuntimeSeconds() {
        double rate = fuelPerSecond();
        return rate > 0.0 ? 64.0 / rate : Double.POSITIVE_INFINITY;
    }
    public long rfPerFuelItem() {
        double rate = fuelPerSecond();
        return rate > 0.0 ? Math.round(exportRate() * 20.0 / rate) : 0L;
    }

    public double coolantPerSecond() {
        if (!usesCoolant()) return 0.0;
        if (status == Status.STEADY) return steadyCoolantPerSecond;
        return ticks > 0 ? 20.0 * coolantItems / ticks : 0.0;
    }
    public double coolantPerMinute() { return coolantPerSecond() * 60.0; }
    public double coolantPerHour() { return coolantPerSecond() * 3600.0; }
    public double coolantStackRuntimeSeconds() {
        double rate = coolantPerSecond();
        return rate > 0.0 ? 64.0 / rate : Double.POSITIVE_INFINITY;
    }

    public static String formatRate(double value) {
        if (value <= 0.0) return "0";
        if (value < 0.01) return String.format(Locale.ROOT, "%.3f", value);
        if (value < 10.0) return String.format(Locale.ROOT, "%.2f", value);
        if (value < 100.0) return String.format(Locale.ROOT, "%.1f", value);
        return String.format(Locale.ROOT, "%.0f", value);
    }

    public static String formatDuration(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) return "∞";
        long total = Math.round(seconds);
        if (total < 60) return total + "s";
        long minutes = total / 60;
        long sec = total % 60;
        if (minutes < 60) return minutes + "m " + sec + "s";
        long hours = minutes / 60;
        long remMin = minutes % 60;
        return hours + "h " + remMin + "m";
    }

    public static String formatEnergy(long rf) {
        if (rf < 1_000L) return rf + " RF";
        if (rf < 1_000_000L) return String.format(Locale.ROOT, "%.1f kRF", rf / 1_000.0);
        if (rf < 1_000_000_000L) return String.format(Locale.ROOT, "%.2f MRF", rf / 1_000_000.0);
        return String.format(Locale.ROOT, "%.2f GRF", rf / 1_000_000_000.0);
    }
}
