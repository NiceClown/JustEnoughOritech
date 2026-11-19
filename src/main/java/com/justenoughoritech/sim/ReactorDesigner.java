package com.justenoughoritech.sim;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Evaluates symmetric layouts with the workbench simulation under a bounded search budget. */
public final class ReactorDesigner {
    private static final int MAX_TICKS = 3_600;
    private static final long SAFE_BUDGET = 10_000_000;
    private static final long GROWTH_BUDGET_PER_SIZE = 5_000_000;
    private static final long RISK_BUDGET = 4_000_000;
    private static final int MAX_ASPECT_RATIO = 2;
    private static final int[][] DIRECTIONS = {{-1,0},{1,0},{0,-1},{0,1}};

    public enum Risk {
        CAUTIOUS(65,false), NORMAL(100,false), AGGRESSIVE(100,true);
        final int maximumHeatPercent;
        final boolean includesDangerous;
        Risk(int maximumHeatPercent,boolean includesDangerous) {
            this.maximumHeatPercent=maximumHeatPercent;
            this.includesDangerous=includesDangerous;
        }
    }

    public enum CoolingMode { AUTO, PASSIVE, WITH_ICE }

    public record Request(long target,Risk risk,Set<String> allowedRods,boolean allowResize,CoolingMode coolingMode) {
        public Request { allowedRods=Set.copyOf(allowedRods); }
        public Request(long target,Risk risk,Set<String> allowedRods,boolean allowResize) {
            this(target,risk,allowedRods,allowResize,CoolingMode.AUTO);
        }
    }

    public record Design(ReactorLayout layout,long exportRate,int peakHeat,int rodBlocks,
                         ReactorSimulation.Status outcome,int ticks,long buildBlocks,long fuelPerTick,
                         double coolantPerSecond) {
        public boolean dangerous() { return outcome!=ReactorSimulation.Status.STEADY; }
    }
    public record Result(Design safe,Design dangerous) {}

    private record Cell(int x,int z) {}
    private record Footprint(int width,int length) {}
    private record CoolingParts(ReactorParts.Part nearRods,ReactorParts.Part nearPipes,boolean oneAbsorberSide) {}
    private enum CoolingPattern {
        PIPES(false,true), SINGLE_REFLECTORS(true,true), DIRECT(false,false),
        REFLECTORS_AND_PIPES(true,true), REFLECTORS(true,false);
        final boolean usesReflectors,usesPipes;
        CoolingPattern(boolean usesReflectors,boolean usesPipes) {
            this.usesReflectors=usesReflectors;
            this.usesPipes=usesPipes;
        }
    }

    private ReactorDesigner() {}

    public static Result find(ReactorLayout size,ReactorConfig settings,List<ReactorParts.Part> parts,
                              ReactorInputs.Fuel fuel,ItemStack coolant,Request request) {
        if (size.width()>settings.maxSize() || size.length()>settings.maxSize()
                || size.height()>settings.maxSize() || request.target()<0)
            return new Result(null,null);

        var rods=parts.stream().filter(p -> p.kind()==ReactorParts.Kind.ROD && request.allowedRods().contains(p.id()))
                .sorted(Comparator.comparingInt(ReactorParts.Part::pulses).reversed()).toList();
        var vent=part(parts,ReactorParts.Kind.VENT);
        var absorber=part(parts,ReactorParts.Kind.ABSORBER);
        var coolers=new ArrayList<CoolingParts>();
        if (vent.kind()!=ReactorParts.Kind.EMPTY && request.coolingMode()!=CoolingMode.WITH_ICE)
            coolers.add(new CoolingParts(vent,vent,false));
        if (absorber.kind()!=ReactorParts.Kind.EMPTY && request.coolingMode()!=CoolingMode.PASSIVE) {
            if (vent.kind()!=ReactorParts.Kind.EMPTY) {
                coolers.add(new CoolingParts(absorber,vent,true));
                coolers.add(new CoolingParts(absorber,vent,false));
                coolers.add(new CoolingParts(vent,absorber,false));
            }
            coolers.add(new CoolingParts(absorber,absorber,false));
        }
        if (rods.isEmpty() || coolers.isEmpty() || settings.rfPerPulse()<=0 || settings.maxEnergyOutputPerPort()<=0)
            return new Result(null,null);
        var pipe=part(parts,ReactorParts.Kind.PIPE);
        var reflector=part(parts,ReactorParts.Kind.REFLECTOR);

        var fixed=new Search(settings,fuel,coolant,request,SAFE_BUDGET,false);
        searchStable(size,rods,coolers,pipe,reflector,fixed);
        boolean resize=request.allowResize() && request.target()>0;
        Design best=fixed.best, danger=fixed.danger;
        if (resize && (!balanced(size) || best==null || best.exportRate()<request.target())) {
            // An existing stretched layout must not win over the compact search solely on output.
            if (!balanced(size)) {
                best=null;
                danger=null;
            }
            int limit=growthLimit(settings.maxSize());
            for (var footprint : footprints(settings,rods.getFirst(),request.target(),size)) {
                if (Thread.currentThread().isInterrupted()) break;
                int width=footprint.width(), length=footprint.length();
                int minimumHeight=Math.max(width,length);
                if (best!=null && best.exportRate()>=request.target()
                        && shapeCost(width,length,minimumHeight)>shapeCost(best.layout())) continue;
                var shape=new ReactorLayout();
                shape.resize(width,length,limit);
                shape.setHeight(minimumHeight,limit);
                var grown=new Search(settings,fuel,coolant,request,GROWTH_BUDGET_PER_SIZE,true);
                grown.best=best;
                grown.danger=danger;
                searchStable(shape,rods,coolers,pipe,reflector,grown);
                best=grown.best;
                danger=grown.danger;
            }
        }

        if (request.risk().includesDangerous && request.coolingMode()!=CoolingMode.WITH_ICE && (best==null ||
                (request.target()>0 && best.exportRate()<request.target()))) {
            // Keep the dangerous fallback on a compact shape when the original was stretched.
            var riskSize=resize && !balanced(size) && best!=null ? best.layout() : size;
            if (!resize || balanced(riskSize)) {
                var aggressive=new Search(settings,fuel,coolant,request,RISK_BUDGET,false);
                aggressive.best=best;
                aggressive.danger=danger;
                for (int spacing : new int[]{1,2,3}) {
                    var groups=rodGroups(riskSize,spacing);
                    for (var rod : rods) {
                        for (int count : counts(groups,request.target(),rodPower(settings,rod,riskSize.height()))) {
                            if (aggressive.exhausted()) break;
                            var layout=candidate(riskSize,groups,count,rod);
                            aggressive.tryLayout(layout,rod,layoutCount(groups,count));
                        }
                    }
                }
                best=aggressive.best;
                danger=aggressive.danger;
            }
        }
        if (best!=null && (request.target()>0 && best.exportRate()>=request.target()
                || danger!=null && danger.exportRate()<=best.exportRate()))
            danger=null;
        return new Result(best,danger);
    }

    private static void searchStable(ReactorLayout size,List<ReactorParts.Part> rods,
                                     List<CoolingParts> coolers,ReactorParts.Part pipe,
                                     ReactorParts.Part reflector,Search search) {
        int centerOnly=Math.max(size.width(),size.length())+1;
        int countHeight=size.height();
        if (search.adjustHeight && search.request.coolingMode()==CoolingMode.WITH_ICE)
            countHeight=ReactorSimulation.coolantAlignedHeight(countHeight,
                    Math.min(growthLimit(search.settings.maxSize()),countHeight+Math.max(1,countHeight/5)));
        for (int pitch : new int[]{3,2,4,centerOnly}) {
            var groups=rodGroups(size,pitch);
            for (var method : CoolingPattern.values()) {
                if (method.usesPipes && pipe.kind()==ReactorParts.Kind.EMPTY
                        || method.usesReflectors && reflector.kind()==ReactorParts.Kind.EMPTY) continue;
                for (var rod : rods) {
                    long perRod=rodPower(search.settings,rod,countHeight);
                    for (int count : counts(groups,search.request.target(),perRod)) {
                        if (search.exhausted()) return;
                        var core=candidate(size,groups,count,rod);
                        int rodCount=layoutCount(groups,count);
                        if (method==CoolingPattern.SINGLE_REFLECTORS)
                            placeOuterReflectors(core,reflector);
                        else if (method.usesReflectors)
                            fillNeighbors(core,ReactorParts.Kind.ROD,reflector,1,0);
                        if (method.usesPipes)
                            fillNeighbors(core,ReactorParts.Kind.ROD,pipe,method==CoolingPattern.REFLECTORS_AND_PIPES ? 0 : 1,
                                    method.usesReflectors ? 1 : 0);
                        for (var cooler : coolers) {
                            if (!method.usesPipes && !cooler.nearRods().equals(cooler.nearPipes())
                                    || cooler.oneAbsorberSide() && method!=CoolingPattern.PIPES) continue;
                            var layout=core.copy();
                            if (cooler.oneAbsorberSide()) {
                                placeOuterCooling(layout,cooler.nearRods());
                                fillNeighbors(layout,ReactorParts.Kind.ROD,cooler.nearPipes(),1,1);
                            } else fillNeighbors(layout,ReactorParts.Kind.ROD,cooler.nearRods(),1,1);
                            if (method.usesPipes)
                                fillNeighbors(layout,ReactorParts.Kind.PIPE,cooler.nearPipes(),1,1);
                            search.tryLayout(layout,rod,rodCount);
                        }
                    }
                }
            }
        }
    }

    private static List<Footprint> footprints(ReactorConfig config,ReactorParts.Part rod,long target,
                                              ReactorLayout base) {
        int limit=growthLimit(config.maxSize());
        // A three-block pitch leaves room between rods for cooling. Use it only to order search.
        int expected=(int)Math.clamp(Math.round(Math.cbrt(9.0*target/rodPower(config,rod,1))),1,limit);
        var sizes=new ArrayList<Integer>();
        for (int side=1;side<=limit;side++) {
            if (side<=8 || side==limit || Math.abs(side-expected)<=4 || side%Math.max(1,side/5)==0)
                sizes.add(side);
        }
        sizes.sort(Comparator.comparingInt(side -> Math.abs(side-expected)));
        var footprints=new LinkedHashSet<Footprint>();
        if (Math.abs(base.width()-base.length())<=1 && base.width()<=limit && base.length()<=limit)
            footprints.add(new Footprint(base.width(),base.length()));
        for (int side : sizes) {
            footprints.add(new Footprint(side,side));
            if (side<limit && Math.abs(side-expected)<=4) footprints.add(new Footprint(side,side+1));
        }
        return List.copyOf(footprints);
    }

    public static int growthLimit(int serverLimit) {
        return Math.min(66,serverLimit);
    }

    private static boolean balanced(ReactorLayout layout) {
        int min=Math.min(layout.height(),Math.min(layout.width(),layout.length()));
        int max=Math.max(layout.height(),Math.max(layout.width(),layout.length()));
        return max<=MAX_ASPECT_RATIO*min;
    }

    private static double shapeCost(ReactorLayout layout) {
        return shapeCost(layout.width(),layout.length(),layout.height());
    }

    private static double shapeCost(int width,int length,int height) {
        int max=Math.max(height,Math.max(width,length));
        int min=Math.min(height,Math.min(width,length));
        double ratio=(double)max/min;
        return (double)width*length*height*ratio*ratio;
    }

    private static long rodPower(ReactorConfig config,ReactorParts.Part rod,int height) {
        return Math.max(1,(long)config.rfPerPulse()*rod.pulses()*height);
    }

    private static List<Integer> centeredAxis(int dimension,int spacing) {
        int margin=spacing>=3 && dimension>=3 ? 1 : 0;
        int available=dimension-2*margin;
        int count=1+(available-1)/spacing;
        if (dimension%2==0 && count%2!=0) count--;
        if (count==0) return List.of(dimension/2-1,dimension/2);
        int start=(dimension-1-(count-1)*spacing)/2;
        var positions=new ArrayList<Integer>(count);
        for (int i=0;i<count;i++) {
            if (i<count/2) positions.add(start+i*spacing);
            else positions.add(dimension-1-(start+(count-1-i)*spacing));
        }
        return positions;
    }

    private static List<List<Cell>> rodGroups(ReactorLayout size,int pitch) {
        var xs=centeredAxis(size.width(),pitch);
        var zs=centeredAxis(size.length(),pitch);
        var groups=new ArrayList<List<Cell>>();
        for (int z : zs) {
            if (2*z>=size.length()) break;
            for (int x : xs) {
                if (2*x>=size.width()) break;
                int mirrorX=size.width()-1-x, mirrorZ=size.length()-1-z;
                var group=new LinkedHashSet<Cell>();
                group.add(new Cell(x,z));
                group.add(new Cell(mirrorX,z));
                group.add(new Cell(x,mirrorZ));
                group.add(new Cell(mirrorX,mirrorZ));
                groups.add(List.copyOf(group));
            }
        }
        groups.sort(Comparator.comparingInt(group -> {
            var cell=group.getFirst();
            int x=size.width()-1-2*cell.x(), z=size.length()-1-2*cell.z();
            return x*x+z*z;
        }));
        return groups;
    }

    private static Set<Integer> counts(List<List<Cell>> groups,long target,long perRod) {
        int max=groups.size();
        int maximumRods=groups.stream().mapToInt(List::size).sum();
        double average=(double)maximumRods/max;
        int ideal=target<=0 ? max : (int)Math.clamp(Math.ceil(target/(perRod*average)),1,max);
        var ordered=new LinkedHashSet<Integer>();
        ordered.add(ideal);
        ordered.add(max);
        if (target>0) {
            ordered.add(Math.max(1,ideal/2));
            ordered.add(Math.max(1,ideal-1));
            ordered.add(Math.min(max,ideal+1));
        }
        ordered.add(1);
        return ordered;
    }

    private static List<Cell> selectedGroup(List<List<Cell>> groups,int index,int count) {
        return groups.get(count==1 ? 0 : index*(groups.size()-1)/(count-1));
    }

    private static int layoutCount(List<List<Cell>> groups,int count) {
        int total=0;
        for (int i=0;i<count;i++) total+=selectedGroup(groups,i,count).size();
        return total;
    }

    private static ReactorLayout candidate(ReactorLayout size,List<List<Cell>> groups,int count,ReactorParts.Part rod) {
        var layout=size.copy();
        layout.clear();
        // Select complete mirror groups and sample from center to edge when using multiple groups.
        for (int i=0;i<count;i++) {
            for (var cell : selectedGroup(groups,i,count)) layout.set(cell.x(),cell.z(),rod);
        }
        return layout;
    }

    private static void fillNeighbors(ReactorLayout layout,ReactorParts.Kind source,ReactorParts.Part fill,
                                      int horizontal,int vertical) {
        for (int z=0;z<layout.length();z++) for (int x=0;x<layout.width();x++) {
            if (layout.get(x,z).kind()!=source) continue;
            for (var direction : DIRECTIONS) {
                int nx=x+direction[0]*horizontal, nz=z+direction[1]*vertical;
                if (nx>=0 && nx<layout.width() && nz>=0 && nz<layout.length()
                        && layout.get(nx,nz).kind()==ReactorParts.Kind.EMPTY) layout.set(nx,nz,fill);
            }
        }
    }

    private static void placeOuterReflectors(ReactorLayout layout,ReactorParts.Part reflector) {
        for (int z=0;z<layout.length();z++) for (int x=1;x<layout.width()-1;x++) {
            if (layout.get(x,z).kind()!=ReactorParts.Kind.ROD) continue;
            int outward=Integer.compare(2*x,layout.width()-1);
            if (layout.get(x+outward,z).kind()==ReactorParts.Kind.EMPTY)
                layout.set(x+outward,z,reflector);
        }
    }

    private static void placeOuterCooling(ReactorLayout layout,ReactorParts.Part absorber) {
        for (int z=1;z<layout.length()-1;z++) for (int x=0;x<layout.width();x++) {
            if (layout.get(x,z).kind()!=ReactorParts.Kind.ROD) continue;
            int outward=Integer.compare(2*z,layout.length()-1);
            if (layout.get(x,z+outward).kind()==ReactorParts.Kind.EMPTY)
                layout.set(x,z+outward,absorber);
        }
    }

    private static boolean better(Design candidate,Design previous,long target) {
        if (previous==null) return true;
        long a=candidate.exportRate(), b=previous.exportRate();
        if (a!=b && (target==0 || a<target || b<target)) return a>b;
        if (candidate.dangerous() && candidate.ticks()!=previous.ticks()) return candidate.ticks()>previous.ticks();
        int shape=Double.compare(shapeCost(candidate.layout()),shapeCost(previous.layout()));
        if (shape!=0) return shape<0;
        if (candidate.fuelPerTick()!=previous.fuelPerTick()) return candidate.fuelPerTick()<previous.fuelPerTick();
        if (candidate.buildBlocks()!=previous.buildBlocks()) return candidate.buildBlocks()<previous.buildBlocks();
        if (candidate.coolantPerSecond()!=previous.coolantPerSecond()) return candidate.coolantPerSecond()<previous.coolantPerSecond();
        if (candidate.peakHeat()!=previous.peakHeat()) return candidate.peakHeat()<previous.peakHeat();
        return a<b;
    }

    private static long ceilDiv(long value,long unit) {
        return value/unit+(value%unit==0 ? 0 : 1);
    }

    private static ReactorParts.Part part(List<ReactorParts.Part> parts,ReactorParts.Kind kind) {
        return parts.stream().filter(p -> p.kind()==kind).findFirst().orElse(ReactorParts.Part.EMPTY);
    }

    private static final class Search {
        private final ReactorConfig settings;
        private final ReactorInputs.Fuel fuel;
        private final ItemStack coolant;
        private final Request request;
        private final boolean adjustHeight;
        private long remaining;
        private Design best,danger;

        private Search(ReactorConfig settings,ReactorInputs.Fuel fuel,ItemStack coolant,Request request,
                       long budget,boolean adjustHeight) {
            this.settings=settings;
            this.fuel=fuel;
            this.coolant=coolant;
            this.request=request;
            this.adjustHeight=adjustHeight;
            remaining=budget;
        }

        boolean exhausted() { return remaining<=0 || Thread.currentThread().isInterrupted(); }

        void tryLayout(ReactorLayout layout,ReactorParts.Part rod,int rods) {
            if (exhausted()) return;
            int width=layout.width(), length=layout.length();
            remaining-=(long)width*length;
            var simulation=new ReactorSimulation(layout,settings,fuel,coolant);
            long perLayer=simulation.generationPerLayer();
            if (perLayer<=0 || request.coolingMode()==CoolingMode.WITH_ICE && !simulation.usesCoolant()) return;
            if (adjustHeight) {
                int maxHeight=Math.min(growthLimit(settings.maxSize()),MAX_ASPECT_RATIO*Math.min(width,length));
                int height=(int)Math.clamp(ceilDiv(request.target(),perLayer),layout.height(),maxHeight);
                // When height divides one ice item's capacity, refills need no idle cooling tick.
                if (simulation.usesCoolant())
                    height=ReactorSimulation.coolantAlignedHeight(height,Math.min(maxHeight,height+Math.max(1,height/5)));
                layout.setHeight(height,maxHeight);
            }
            int height=layout.height();
            long generation=perLayer*height;
            long needed=request.target()>0 ? Math.min(request.target(),generation) : generation;
            layout.setEnergyPorts((int)Math.clamp(ceilDiv(needed,settings.maxEnergyOutputPerPort()),
                    1,ReactorLayout.maxEnergyPorts(width,length,height)));
            long export=Math.min(generation,(long)layout.energyPorts()*settings.maxEnergyOutputPerPort());
            if (best!=null && export<best.exportRate() && (request.target()==0 || export<request.target())) return;

            long shell=(long)(width+2)*(length+2)*(height+2)-(long)width*length*height;
            long buildBlocks=shell+(long)simulation.totalCells()*height;
            long fuelPerTick=(long)rod.rodCount()*rods*height;
            if (best!=null && best.exportRate()>=request.target()
                    && (request.target()>0 || export==best.exportRate())
                    && (shapeCost(layout)>shapeCost(best.layout()) || shapeCost(layout)==shapeCost(best.layout())
                    && (fuelPerTick>best.fuelPerTick()
                    || fuelPerTick==best.fuelPerTick() && buildBlocks>best.buildBlocks()))) return;

            simulation.start();
            for (int tick=0;tick<MAX_TICKS && !exhausted();tick++) {
                simulation.tick();
                remaining-=simulation.totalCells();
                if (simulation.status()!=ReactorSimulation.Status.RUNNING) break;
            }
            var status=simulation.status();
            var result=new Design(layout,simulation.exportRate(),simulation.maxHeatSeen(),
                    rods*height,status,simulation.ticks(),buildBlocks,fuelPerTick,simulation.steadyCoolantPerSecond());
            if (status==ReactorSimulation.Status.STEADY &&
                    (long)result.peakHeat()*100 <= (long)settings.maxHeat()*request.risk().maximumHeatPercent) {
                if (better(result,best,request.target())) best=result;
            } else if (request.risk().includesDangerous &&
                    (status==ReactorSimulation.Status.MELTDOWN || status==ReactorSimulation.Status.SAFE_COOLDOWN)) {
                if (better(result,danger,request.target())) danger=result;
            }
        }
    }
}
