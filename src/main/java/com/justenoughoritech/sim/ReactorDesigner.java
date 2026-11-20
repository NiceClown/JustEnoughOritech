package com.justenoughoritech.sim;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Evaluates symmetric layouts with the workbench simulation under a bounded, cancellable search budget. */
public final class ReactorDesigner {
    private static final int MAX_TICKS = 3_600;
    private static final long FIXED_BUDGET = 12_000_000L;
    private static final long GROWTH_BUDGET = 28_000_000L;
    private static final long REFINE_BUDGET = 8_000_000L;
    private static final long RISK_BUDGET = 8_000_000L;
    private static final long LOCAL_BUDGET = 4_000_000L;
    private static final int LOCAL_ITERATIONS = 24;
    private static final int SHORTLIST_SIZE = 5;
    private static final int SHORTLIST_LIMIT = 32;
    private static final long SEARCH_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final int MAX_ASPECT_RATIO = 2;
    private static final int[][] DIRECTIONS = {{-1,0},{1,0},{0,-1},{0,1}};
    /** Stable-search pitches. Anisotropic pairs let heat escape along a dense axis. */
    private static final int[][] PITCHES = {
            {3,3},{2,2},{4,4},{2,3},{3,2},{1,2},{2,1},{1,3},{3,1}
    };

    public enum Risk {
        CAUTIOUS(65,false), NORMAL(100,false), AGGRESSIVE(100,true);
        final int maximumHeatPercent;
        final boolean includesDangerous;
        Risk(int maximumHeatPercent,boolean includesDangerous) {
            this.maximumHeatPercent=maximumHeatPercent; this.includesDangerous=includesDangerous;
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
                         double coolantPerSecond,int[] heat) {
        public Design {
            heat = heat == null ? new int[0] : heat.clone();
        }
        public boolean dangerous() { return outcome!=ReactorSimulation.Status.STEADY; }
        @Override public int[] heat() { return heat.clone(); }
    }
    public record Result(Design safe,Design dangerous) {}

    private record Cell(int x,int z) {}
    private record Footprint(int width,int length) {}
    private record RodBounds(int minX,int maxX,int minZ,int maxZ) {}
    private record RodHeatGroup(List<Cell> cells,double average) {}
    /** Canonical 2D layout key. Transposed layouts normalise to the same key. */
    private record LayoutKey(int width,int length,int height,List<String> cells) {
        private static LayoutKey of(ReactorLayout layout) {
            int width=layout.width(), length=layout.length();
            var cells=new ArrayList<String>(width*length);
            if (width<=length) {
                for (int z=0;z<length;z++) for (int x=0;x<width;x++) cells.add(layout.get(x,z).id());
            } else {
                for (int oldX=0;oldX<width;oldX++) for (int oldZ=0;oldZ<length;oldZ++)
                    cells.add(layout.get(oldX,oldZ).id());
            }
            return new LayoutKey(Math.min(width,length),Math.max(width,length),layout.height(),List.copyOf(cells));
        }
    }
    private record CoolingParts(ReactorParts.Part nearRods,ReactorParts.Part nearPipes,boolean oneAbsorberSide) {}
    private enum CoolingPattern {
        PIPES(false,true), SINGLE_REFLECTORS(true,true), DIRECT(false,false),
        REFLECTORS_AND_PIPES(true,true), REFLECTORS(true,false);
        final boolean usesReflectors,usesPipes;
        CoolingPattern(boolean usesReflectors,boolean usesPipes) {
            this.usesReflectors=usesReflectors; this.usesPipes=usesPipes;
        }
    }

    private ReactorDesigner() {}

    public static Result find(ReactorLayout size,ReactorConfig settings,List<ReactorParts.Part> parts,
                              ReactorInputs.Fuel fuel,ItemStack coolant,Request request) {
        return find(size,settings,parts,fuel,coolant,request,() -> false);
    }

    public static Result find(ReactorLayout size,ReactorConfig settings,List<ReactorParts.Part> parts,
                              ReactorInputs.Fuel fuel,ItemStack coolant,Request request,
                              BooleanSupplier cancelled) {
        BooleanSupplier stop=cancelled==null ? () -> false : cancelled;
        if (size.width()>settings.maxSize() || size.length()>settings.maxSize()
                || size.height()>settings.maxSize() || request.target()<0 || stop.getAsBoolean())
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

        long deadline=System.nanoTime()+SEARCH_NANOS;
        var shortlist=new Shortlist(request.target());
        var fixedBudget=new Budget(FIXED_BUDGET,deadline,stop);
        var fixed=new Search(settings,fuel,coolant,request,fixedBudget,false,false,shortlist);
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
            var growthBudget=new Budget(GROWTH_BUDGET,deadline,stop);
            var footprints=footprints(settings,rods.getFirst(),request.target(),size);
            int total=footprints.size();
            for (int i=0;i<total;i++) {
                if (growthBudget.exhausted()) break;
                var footprint=footprints.get(i);
                int width=footprint.width(), length=footprint.length();
                int minimumHeight=Math.max(width,length);
                if (best!=null && best.exportRate()>=request.target()
                        && shapeCost(width,length,minimumHeight)>shapeCost(best.layout())) continue;
                long fairShare=growthBudget.remaining()/Math.max(1,total-i);
                long perFootprint=Math.min(growthBudget.remaining(),Math.max(1,fairShare*2));
                var footprintBudget=growthBudget.child(perFootprint);
                var shape=new ReactorLayout();
                shape.resize(width,length,limit);
                shape.setHeight(minimumHeight,limit);
                var grown=new Search(settings,fuel,coolant,request,footprintBudget,true,false,shortlist);
                grown.best=best;
                grown.danger=danger;
                searchStable(shape,rods,coolers,pipe,reflector,grown);
                growthBudget.absorb(footprintBudget);
                best=grown.best;
                danger=grown.danger;
            }
        }

        // Top-K refinement: mixed rod types, boundary/core swaps, cooling rings and atomic pipe + second ring.
        if (best!=null && !stop.getAsBoolean()) {
            var seeds=shortlist.top(SHORTLIST_SIZE);
            if (seeds.isEmpty()) seeds=List.of(best);
            long perSeed=Math.max(1,REFINE_BUDGET/Math.max(1,seeds.size()));
            for (var seed : seeds) {
                if (stop.getAsBoolean()) break;
                var refineBudget=new Budget(perSeed,deadline,stop);
                var refine=new Search(settings,fuel,coolant,request,refineBudget,resize,resize,null);
                refine.best=best;
                refine.danger=danger;
                refine.tryLayout(seed.layout().copy());
                refineBest(seed,rods,vent,absorber,pipe,reflector,refine);
                best=refine.best;
                danger=refine.danger;
            }
        }

        // Temperature-guided local search on the current winner.
        if (best!=null && !stop.getAsBoolean()) {
            var localBudget=new Budget(LOCAL_BUDGET,deadline,stop);
            var local=new Search(settings,fuel,coolant,request,localBudget,resize,resize,null);
            local.best=best;
            local.danger=danger;
            localSearch(rods,vent,absorber,reflector,local);
            best=local.best;
            danger=local.danger;
        }

        if (request.risk().includesDangerous && request.coolingMode()!=CoolingMode.WITH_ICE && (best==null ||
                (request.target()>0 && best.exportRate()<request.target()))) {
            // Keep the dangerous fallback on a compact shape when the original was stretched.
            var riskSize=resize && !balanced(size) && best!=null ? best.layout() : size;
            if (!resize || balanced(riskSize)) {
                var riskBudget=new Budget(RISK_BUDGET,deadline,stop);
                var aggressive=new Search(settings,fuel,coolant,request,riskBudget,false,false,null);
                aggressive.best=best;
                aggressive.danger=danger;
                for (int spacing : new int[]{1,2,3}) {
                    if (aggressive.exhausted()) break;
                    var groups=rodGroups(riskSize,spacing,spacing);
                    for (var rod : rods) {
                        for (int count : counts(groups,request.target(),rodPower(settings,rod,riskSize.height()))) {
                            if (aggressive.exhausted()) break;
                            var layout=candidate(riskSize,groups,count,rod);
                            aggressive.tryLayout(layout);
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
        for (int[] pitch : PITCHES) {
            boolean verticalChannels=pitch[1]>=pitch[0];
            searchGroups(size,rodGroups(size,pitch[0],pitch[1]),rods,coolers,pipe,reflector,
                    search,countHeight,verticalChannels);
        }
        searchGroups(size,rodGroups(size,centerOnly,centerOnly),rods,coolers,pipe,reflector,
                search,countHeight,true);
    }

    private static void searchGroups(ReactorLayout size,List<List<Cell>> groups,
                                     List<ReactorParts.Part> rods,List<CoolingParts> coolers,
                                     ReactorParts.Part pipe,ReactorParts.Part reflector,
                                     Search search,int countHeight,boolean verticalChannels) {
        int reflectorH=verticalChannels ? 1 : 0;
        int reflectorV=verticalChannels ? 0 : 1;
        int pipeH=verticalChannels ? 0 : 1;
        int pipeV=verticalChannels ? 1 : 0;
        for (var method : CoolingPattern.values()) {
            if (search.exhausted()) return;
            if (method.usesPipes && pipe.kind()==ReactorParts.Kind.EMPTY
                    || method.usesReflectors && reflector.kind()==ReactorParts.Kind.EMPTY) continue;
            for (var rod : rods) {
                if (search.exhausted()) return;
                long perRod=rodPower(search.settings,rod,countHeight);
                for (int count : counts(groups,search.request.target(),perRod)) {
                    if (search.exhausted()) return;
                    var core=candidate(size,groups,count,rod);
                    if (method==CoolingPattern.SINGLE_REFLECTORS)
                        placeOuterReflectors(core,reflector,verticalChannels);
                    else if (method.usesReflectors)
                        fillNeighbors(core,ReactorParts.Kind.ROD,reflector,reflectorH,reflectorV);
                    if (method.usesPipes) {
                        int horizontal=method==CoolingPattern.SINGLE_REFLECTORS ? 1 : pipeH;
                        int vertical=method==CoolingPattern.SINGLE_REFLECTORS ? 1 : pipeV;
                        fillNeighbors(core,ReactorParts.Kind.ROD,pipe,horizontal,vertical);
                    }
                    for (var cooler : coolers) {
                        if (search.exhausted()) return;
                        if (!method.usesPipes && !cooler.nearRods().equals(cooler.nearPipes())
                                || cooler.oneAbsorberSide() && method!=CoolingPattern.PIPES) continue;
                        var layout=core.copy();
                        if (cooler.oneAbsorberSide()) {
                            placeOuterCooling(layout,cooler.nearRods(),!verticalChannels);
                            fillNeighbors(layout,ReactorParts.Kind.ROD,cooler.nearPipes(),1,1);
                        } else fillNeighbors(layout,ReactorParts.Kind.ROD,cooler.nearRods(),1,1);
                        if (method.usesPipes)
                            fillNeighbors(layout,ReactorParts.Kind.PIPE,cooler.nearPipes(),1,1);
                        search.tryLayout(layout);
                    }
                }
            }
        }
    }

    /**
     * Greedy symmetric refinement. It explores mixed rod types and cooling-ring changes
     * that the regular grouped search cannot express.
     */
    private static void refineBest(Design seed,List<ReactorParts.Part> rods,ReactorParts.Part vent,
                                   ReactorParts.Part absorber,ReactorParts.Part pipe,
                                   ReactorParts.Part reflector,Search search) {
        if (seed==null || search.exhausted()) return;
        Design current=seed;
        for (var type : rods) {
            if (search.exhausted()) return;
            var layout=current.layout().copy();
            if (replaceRods(layout,type)) {
                search.tryLayout(layout);
                current=refined(search,current);
            }
        }
        if (rods.size()>1) {
            for (var type : rods) {
                if (search.exhausted()) return;
                var boundary=current.layout().copy();
                if (replaceRodRing(boundary,type,true)) {
                    search.tryLayout(boundary);
                    current=refined(search,current);
                }
                if (search.exhausted()) return;
                var core=current.layout().copy();
                if (replaceRodRing(core,type,false)) {
                    search.tryLayout(core);
                    current=refined(search,current);
                }
            }
            for (var inner : rods) for (var outer : rods) {
                if (inner==outer) continue;
                if (search.exhausted()) return;
                var checker=current.layout().copy();
                if (mixRods(checker,inner,outer,true)) {
                    search.tryLayout(checker);
                    current=refined(search,current);
                }
                if (search.exhausted()) return;
                var center=current.layout().copy();
                if (mixRods(center,inner,outer,false)) {
                    search.tryLayout(center);
                    current=refined(search,current);
                }
            }
        }
        var mode=search.request.coolingMode();
        for (var target : new ReactorParts.Part[]{absorber,vent,reflector}) {
            if (target.kind()==ReactorParts.Kind.EMPTY || search.exhausted()
                    || !coolingAllowed(mode,target.kind())) continue;
            var layout=current.layout().copy();
            if (replaceNearestRing(layout,target)) {
                search.tryLayout(layout);
                current=refined(search,current);
            }
        }
        // Atomic pipe ring + second ring. The intermediate pipe-only layout is not tested:
        // it usually melts down and would prevent the second ring from ever being built.
        if (pipe.kind()!=ReactorParts.Kind.EMPTY) {
            for (var coolant : new ReactorParts.Part[]{absorber,vent}) {
                if (coolant.kind()==ReactorParts.Kind.EMPTY || search.exhausted()
                        || !coolingAllowed(mode,coolant.kind())) continue;
                var layout=current.layout().copy();
                if (replacePipeRing(layout,pipe,coolant)) {
                    search.tryLayout(layout);
                    current=refined(search,current);
                }
            }
        }
    }

    private static boolean replacePipeRing(ReactorLayout layout,ReactorParts.Part pipe,ReactorParts.Part coolant) {
        boolean changed=replaceNearestRing(layout,pipe);
        changed|=replaceSecondRing(layout,coolant);
        return changed;
    }

    private static boolean coolingAllowed(CoolingMode mode,ReactorParts.Kind kind) {
        return !(mode==CoolingMode.PASSIVE && kind==ReactorParts.Kind.ABSORBER)
                && !(mode==CoolingMode.WITH_ICE && kind==ReactorParts.Kind.VENT);
    }

    private static Design refined(Search search,Design fallback) {
        return search.best!=null ? search.best : fallback;
    }

    private static boolean replaceRods(ReactorLayout layout,ReactorParts.Part type) {
        boolean changed=false;
        for (int z=0;z<layout.length();z++) for (int x=0;x<layout.width();x++) {
            var part=layout.get(x,z);
            if (part.kind()==ReactorParts.Kind.ROD && !part.id().equals(type.id())) {
                layout.set(x,z,type);
                changed=true;
            }
        }
        return changed;
    }

    private static boolean replaceRodRing(ReactorLayout layout,ReactorParts.Part type,boolean boundary) {
        var bounds=rodBounds(layout);
        if (bounds==null) return false;
        boolean changed=false;
        for (int z=0;z<layout.length();z++) for (int x=0;x<layout.width();x++) {
            var part=layout.get(x,z);
            if (part.kind()!=ReactorParts.Kind.ROD) continue;
            boolean edge=x==bounds.minX() || x==bounds.maxX() || z==bounds.minZ() || z==bounds.maxZ();
            if (edge==boundary && !part.id().equals(type.id())) {
                layout.set(x,z,type);
                changed=true;
            }
        }
        return changed;
    }

    private static boolean mixRods(ReactorLayout layout,ReactorParts.Part inner,ReactorParts.Part outer,
                                   boolean checkerboard) {
        var xs=new TreeSet<Integer>();
        var zs=new TreeSet<Integer>();
        for (int z=0;z<layout.length();z++) for (int x=0;x<layout.width();x++) {
            if (layout.get(x,z).kind()==ReactorParts.Kind.ROD) {
                xs.add(x);
                zs.add(z);
            }
        }
        if (xs.isEmpty() || zs.isEmpty()) return false;
        var xIndex=new HashMap<Integer,Integer>();
        var zIndex=new HashMap<Integer,Integer>();
        int index=0;
        for (int x : xs) xIndex.put(x,index++);
        index=0;
        for (int z : zs) zIndex.put(z,index++);
        double cx=(layout.width()-1)/2.0, cz=(layout.length()-1)/2.0;
        double radius=Math.max(1.0,Math.min(layout.width(),layout.length())/3.0);
        boolean changed=false;
        for (int z=0;z<layout.length();z++) for (int x=0;x<layout.width();x++) {
            if (layout.get(x,z).kind()!=ReactorParts.Kind.ROD) continue;
            boolean useInner;
            if (checkerboard) {
                useInner=((xIndex.get(x)+zIndex.get(z))&1)==0;
            } else {
                double dx=x-cx, dz=z-cz;
                useInner=dx*dx+dz*dz<=radius*radius;
            }
            var target=useInner ? inner : outer;
            if (!layout.get(x,z).id().equals(target.id())) {
                layout.set(x,z,target);
                changed=true;
            }
        }
        return changed;
    }

    private static RodBounds rodBounds(ReactorLayout layout) {
        int minX=Integer.MAX_VALUE, maxX=Integer.MIN_VALUE, minZ=Integer.MAX_VALUE, maxZ=Integer.MIN_VALUE;
        for (int z=0;z<layout.length();z++) for (int x=0;x<layout.width();x++) {
            if (layout.get(x,z).kind()!=ReactorParts.Kind.ROD) continue;
            minX=Math.min(minX,x);
            maxX=Math.max(maxX,x);
            minZ=Math.min(minZ,z);
            maxZ=Math.max(maxZ,z);
        }
        return minX==Integer.MAX_VALUE ? null : new RodBounds(minX,maxX,minZ,maxZ);
    }

    private static boolean replaceNearestRing(ReactorLayout layout,ReactorParts.Part target) {
        boolean changed=false;
        for (int z=0;z<layout.length();z++) for (int x=0;x<layout.width();x++) {
            var part=layout.get(x,z);
            if (part.kind()==ReactorParts.Kind.ROD) continue;
            if (!adjacentToKind(layout,x,z,ReactorParts.Kind.ROD)) continue;
            if (!part.id().equals(target.id())) {
                layout.set(x,z,target);
                changed=true;
            }
        }
        return changed;
    }

    private static boolean replaceSecondRing(ReactorLayout layout,ReactorParts.Part target) {
        boolean changed=false;
        for (int z=0;z<layout.length();z++) for (int x=0;x<layout.width();x++) {
            var part=layout.get(x,z);
            if (part.kind()!=ReactorParts.Kind.EMPTY && part.kind()!=ReactorParts.Kind.VENT
                    && part.kind()!=ReactorParts.Kind.ABSORBER) continue;
            if (part.id().equals(target.id())) continue;
            if (!adjacentToKind(layout,x,z,ReactorParts.Kind.PIPE)) continue;
            if (adjacentToKind(layout,x,z,ReactorParts.Kind.ROD)) continue;
            layout.set(x,z,target);
            changed=true;
        }
        return changed;
    }

    private static boolean adjacentToKind(ReactorLayout layout,int x,int z,ReactorParts.Kind kind) {
        for (var direction : DIRECTIONS) {
            int nx=x+direction[0], nz=z+direction[1];
            if (nx>=0 && nx<layout.width() && nz>=0 && nz<layout.length()
                    && layout.get(nx,nz).kind()==kind) return true;
        }
        return false;
    }

    /**
     * Short symmetric hill-climb guided by the temperatures of the current winner.
     * It upgrades cold rod groups and cools hot groups, one mirror-symmetric change per iteration.
     */
    private static void localSearch(List<ReactorParts.Part> rods,ReactorParts.Part vent,
                                    ReactorParts.Part absorber,ReactorParts.Part reflector,Search search) {
        if (search.best==null || search.exhausted()) return;
        var orderedRods=rods.stream().sorted(Comparator.comparingInt(ReactorParts.Part::rodCount)).toList();
        var mode=search.request.coolingMode();
        int limit=(int)((long)search.settings.maxHeat()*search.request.risk().maximumHeatPercent/100);
        for (int iteration=0;iteration<LOCAL_ITERATIONS;iteration++) {
            if (search.exhausted()) return;
            var current=search.best;
            var groups=rodHeatGroups(current);
            if (groups.isEmpty()) return;
            groups.sort(Comparator.comparingDouble(RodHeatGroup::average));
            boolean headroom=(long)current.peakHeat()*4<(long)limit*3;
            var variants=new ArrayList<ReactorLayout>();
            if (headroom) {
                for (int i=0;i<Math.min(3,groups.size());i++) {
                    var group=groups.get(i);
                    addRodTypeVariant(variants,current,group,orderedRods,1);
                    addAdjacentVariant(variants,current,group,reflector,Set.of(ReactorParts.Kind.EMPTY));
                }
            } else {
                for (int i=0;i<Math.min(3,groups.size());i++) {
                    var group=groups.get(groups.size()-1-i);
                    addRodTypeVariant(variants,current,group,orderedRods,-1);
                    if (mode!=CoolingMode.WITH_ICE)
                        addAdjacentVariant(variants,current,group,vent,
                                Set.of(ReactorParts.Kind.EMPTY,ReactorParts.Kind.PIPE,ReactorParts.Kind.REFLECTOR));
                    if (mode!=CoolingMode.PASSIVE)
                        addAdjacentVariant(variants,current,group,absorber,
                                Set.of(ReactorParts.Kind.EMPTY,ReactorParts.Kind.PIPE,ReactorParts.Kind.REFLECTOR));
                }
            }
            boolean improved=false;
            for (var variant : variants) {
                if (search.exhausted()) return;
                var before=search.best;
                search.tryLayout(variant);
                if (search.best!=before) {
                    improved=true;
                    break;
                }
            }
            if (!improved) return;
        }
    }

    private static void addRodTypeVariant(List<ReactorLayout> out,Design design,RodHeatGroup group,
                                          List<ReactorParts.Part> orderedRods,int direction) {
        if (group.cells().isEmpty()) return;
        var first=group.cells().getFirst();
        var current=design.layout().get(first.x(),first.z());
        if (current.kind()!=ReactorParts.Kind.ROD) return;
        int index=-1;
        for (int i=0;i<orderedRods.size();i++) {
            if (orderedRods.get(i).id().equals(current.id())) {
                index=i;
                break;
            }
        }
        if (index<0) return;
        int next=index+direction;
        if (next<0 || next>=orderedRods.size()) return;
        var target=orderedRods.get(next);
        var layout=design.layout().copy();
        if (fillGroup(layout,group.cells(),target,part -> part.kind()==ReactorParts.Kind.ROD))
            out.add(layout);
    }

    private static void addAdjacentVariant(List<ReactorLayout> out,Design design,RodHeatGroup group,
                                           ReactorParts.Part target,Set<ReactorParts.Kind> allowed) {
        if (target.kind()==ReactorParts.Kind.EMPTY) return;
        var layout=design.layout();
        var keys=new HashSet<String>();
        for (var cell : group.cells()) {
            for (var direction : DIRECTIONS) {
                int x=cell.x()+direction[0], z=cell.z()+direction[1];
                if (x<0 || x>=layout.width() || z<0 || z>=layout.length()) continue;
                var mirror=mirrorCells(layout,x,z);
                if (!keys.add(groupKey(mirror))) continue;
                var candidate=design.layout().copy();
                if (fillGroup(candidate,mirror,target,part -> allowed.contains(part.kind())))
                    out.add(candidate);
            }
        }
    }

    private static List<RodHeatGroup> rodHeatGroups(Design design) {
        var layout=design.layout();
        var heat=design.heat();
        int width=layout.width();
        var unique=new LinkedHashMap<String,List<Cell>>();
        for (int z=0;z<layout.length();z++) for (int x=0;x<layout.width();x++) {
            if (layout.get(x,z).kind()!=ReactorParts.Kind.ROD) continue;
            var cells=mirrorCells(layout,x,z);
            unique.putIfAbsent(groupKey(cells),cells);
        }
        var groups=new ArrayList<RodHeatGroup>();
        for (var cells : unique.values()) {
            int sum=0, count=0;
            boolean allRods=true;
            for (var cell : cells) {
                var part=layout.get(cell.x(),cell.z());
                if (part.kind()!=ReactorParts.Kind.ROD) {
                    allRods=false;
                    break;
                }
                sum+=heat[cell.z()*width+cell.x()];
                count++;
            }
            if (allRods && count>0) groups.add(new RodHeatGroup(cells,(double)sum/count));
        }
        return groups;
    }

    private static List<Cell> mirrorCells(ReactorLayout layout,int x,int z) {
        int mx=layout.width()-1-x, mz=layout.length()-1-z;
        var cells=new LinkedHashSet<Cell>();
        cells.add(new Cell(x,z));
        cells.add(new Cell(mx,z));
        cells.add(new Cell(x,mz));
        cells.add(new Cell(mx,mz));
        return List.copyOf(cells);
    }

    private static String groupKey(List<Cell> cells) {
        return cells.stream().map(cell -> cell.x()+":"+cell.z()).sorted().toList().toString();
    }

    private static boolean fillGroup(ReactorLayout layout,List<Cell> cells,ReactorParts.Part target,
                                     Predicate<ReactorParts.Part> allowed) {
        for (var cell : cells) {
            if (!allowed.test(layout.get(cell.x(),cell.z()))) return false;
        }
        boolean changed=false;
        for (var cell : cells) {
            var part=layout.get(cell.x(),cell.z());
            if (!part.id().equals(target.id())) {
                layout.set(cell.x(),cell.z(),target);
                changed=true;
            }
        }
        return changed;
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
            footprints.add(new Footprint(Math.min(base.width(),base.length()),Math.max(base.width(),base.length())));
        for (int side : sizes) {
            footprints.add(new Footprint(side,side));
            if (side<limit && Math.abs(side-expected)<=4)
                footprints.add(new Footprint(side,side+1));
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

    private static List<List<Cell>> rodGroups(ReactorLayout size,int pitchX,int pitchZ) {
        var xs=centeredAxis(size.width(),pitchX);
        var zs=centeredAxis(size.length(),pitchZ);
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
            ordered.add(Math.max(1,ideal-2));
            ordered.add(Math.min(max,ideal+2));
            ordered.add(Math.max(1,ideal*3/4));
            ordered.add(Math.min(max,ideal*5/4));
        }
        ordered.add(1);
        return ordered;
    }

    private static List<Cell> selectedGroup(List<List<Cell>> groups,int index,int count) {
        return groups.get(count==1 ? 0 : index*(groups.size()-1)/(count-1));
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

    private static void placeOuterReflectors(ReactorLayout layout,ReactorParts.Part reflector,boolean alongX) {
        if (alongX) {
            for (int z=0;z<layout.length();z++) for (int x=1;x<layout.width()-1;x++) {
                if (layout.get(x,z).kind()!=ReactorParts.Kind.ROD) continue;
                int outward=Integer.compare(2*x,layout.width()-1);
                if (outward==0) placeSides(layout,x,z,reflector,true);
                else if (layout.get(x+outward,z).kind()==ReactorParts.Kind.EMPTY)
                    layout.set(x+outward,z,reflector);
            }
        } else {
            for (int x=0;x<layout.width();x++) for (int z=1;z<layout.length()-1;z++) {
                if (layout.get(x,z).kind()!=ReactorParts.Kind.ROD) continue;
                int outward=Integer.compare(2*z,layout.length()-1);
                if (outward==0) placeSides(layout,x,z,reflector,false);
                else if (layout.get(x,z+outward).kind()==ReactorParts.Kind.EMPTY)
                    layout.set(x,z+outward,reflector);
            }
        }
    }

    private static void placeOuterCooling(ReactorLayout layout,ReactorParts.Part absorber,boolean alongX) {
        if (alongX) {
            for (int x=1;x<layout.width()-1;x++) for (int z=0;z<layout.length();z++) {
                if (layout.get(x,z).kind()!=ReactorParts.Kind.ROD) continue;
                int outward=Integer.compare(2*x,layout.width()-1);
                if (outward==0) placeSides(layout,x,z,absorber,true);
                else if (layout.get(x+outward,z).kind()==ReactorParts.Kind.EMPTY)
                    layout.set(x+outward,z,absorber);
            }
        } else {
            for (int z=1;z<layout.length()-1;z++) for (int x=0;x<layout.width();x++) {
                if (layout.get(x,z).kind()!=ReactorParts.Kind.ROD) continue;
                int outward=Integer.compare(2*z,layout.length()-1);
                if (outward==0) placeSides(layout,x,z,absorber,false);
                else if (layout.get(x,z+outward).kind()==ReactorParts.Kind.EMPTY)
                    layout.set(x,z+outward,absorber);
            }
        }
    }

    private static void placeSides(ReactorLayout layout,int x,int z,ReactorParts.Part part,boolean alongX) {
        if (alongX) {
            if (x>0 && layout.get(x-1,z).kind()==ReactorParts.Kind.EMPTY) layout.set(x-1,z,part);
            if (x+1<layout.width() && layout.get(x+1,z).kind()==ReactorParts.Kind.EMPTY) layout.set(x+1,z,part);
        } else {
            if (z>0 && layout.get(x,z-1).kind()==ReactorParts.Kind.EMPTY) layout.set(x,z-1,part);
            if (z+1<layout.length() && layout.get(x,z+1).kind()==ReactorParts.Kind.EMPTY) layout.set(x,z+1,part);
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
        return a>b;
    }

    private static long ceilDiv(long value,long unit) {
        return value/unit+(value%unit==0 ? 0 : 1);
    }

    private static ReactorParts.Part part(List<ReactorParts.Part> parts,ReactorParts.Kind kind) {
        return parts.stream().filter(p -> p.kind()==kind).findFirst().orElse(ReactorParts.Part.EMPTY);
    }

    /** Small diversified shortlist of good stable candidates, used as refinement seeds. */
    private static final class Shortlist {
        private final long target;
        private final Map<String,Design> designs=new LinkedHashMap<>();

        private Shortlist(long target) { this.target=target; }

        private void offer(Design design) {
            if (design==null || design.dangerous()) return;
            var key=key(design.layout());
            var previous=designs.get(key);
            if (previous==null || better(design,previous,target)) designs.put(key,design);
            while (designs.size()>SHORTLIST_LIMIT) {
                var entries=new ArrayList<>(designs.entrySet());
                entries.sort((a,b) -> better(a.getValue(),b.getValue(),target) ? -1
                        : better(b.getValue(),a.getValue(),target) ? 1 : 0);
                designs.remove(entries.getLast().getKey());
            }
        }

        private List<Design> top(int count) {
            var sorted=new ArrayList<>(designs.values());
            sorted.sort((a,b) -> better(a,b,target) ? -1 : better(b,a,target) ? 1 : 0);
            return List.copyOf(sorted.subList(0,Math.min(count,sorted.size())));
        }

        private static String key(ReactorLayout layout) {
            int rodCells=0, absorbers=0, vents=0, pipes=0, reflectors=0;
            var rodIds=new TreeSet<String>();
            for (int z=0;z<layout.length();z++) for (int x=0;x<layout.width();x++) {
                var part=layout.get(x,z);
                switch (part.kind()) {
                    case ROD -> {
                        rodCells++;
                        rodIds.add(part.id());
                    }
                    case ABSORBER -> absorbers++;
                    case VENT -> vents++;
                    case PIPE -> pipes++;
                    case REFLECTOR -> reflectors++;
                    default -> { }
                }
            }
            return Math.min(layout.width(),layout.length())+"x"+Math.max(layout.width(),layout.length())
                    +"x"+layout.height()+":"+rodCells+":"+absorbers+":"+vents+":"+pipes+":"+reflectors
                    +":"+String.join(",",rodIds);
        }
    }

    /** Shared cell-tick and wall-clock budget. All phases draw from their own budget but share the deadline. */
    private static final class Budget {
        private long remaining;
        private long spent;
        private final long deadline;
        private final BooleanSupplier cancelled;

        private Budget(long remaining,long deadline,BooleanSupplier cancelled) {
            this.remaining=remaining;
            this.deadline=deadline;
            this.cancelled=cancelled;
        }

        private boolean exhausted() {
            return remaining<=0 || System.nanoTime()>=deadline
                    || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
        }

        private void spend(long amount) {
            remaining-=amount;
            spent+=amount;
        }

        private long remaining() {
            return remaining;
        }

        private Budget child(long limit) {
            return new Budget(Math.min(limit,remaining),deadline,cancelled);
        }

        private void absorb(Budget child) {
            remaining=Math.max(0,remaining-child.spent);
        }
    }

    /**
     * O(1) upper-bound check before running a simulation: a rod without any cooling neighbour,
     * or total heat generation above the maximum cooling capacity, cannot reach a stable state.
     */
    private static boolean thermallyImpossible(ReactorSimulation simulation,ReactorLayout layout,
                                               ReactorConfig settings,Request request) {
        if (request.risk().includesDangerous) return false;
        int maxHeat=(int)((long)settings.maxHeat()*request.risk().maximumHeatPercent/100);
        long generated=0, absorbed=0;
        int vents=0;
        for (int z=0;z<layout.length();z++) for (int x=0;x<layout.width();x++) {
            var cell=simulation.cell(x,z);
            if (cell==null) continue;
            if (cell.part.kind()==ReactorParts.Kind.ROD) {
                generated+=(long)(cell.pulses/2)*cell.pulses+4;
                boolean cooling=false;
                for (var direction : DIRECTIONS) {
                    int nx=x+direction[0], nz=z+direction[1];
                    if (nx<0 || nx>=layout.width() || nz<0 || nz>=layout.length()) continue;
                    var kind=layout.get(nx,nz).kind();
                    if (kind==ReactorParts.Kind.VENT || kind==ReactorParts.Kind.PIPE
                            || kind==ReactorParts.Kind.ABSORBER) {
                        cooling=true;
                        break;
                    }
                }
                if (!cooling) return true;
            } else if (cell.part.kind()==ReactorParts.Kind.VENT) {
                vents++;
            } else if (cell.part.kind()==ReactorParts.Kind.ABSORBER) {
                int hotNeighbors=0;
                for (var direction : DIRECTIONS) {
                    int nx=x+direction[0], nz=z+direction[1];
                    if (nx<0 || nx>=layout.width() || nz<0 || nz>=layout.length()) continue;
                    var kind=layout.get(nx,nz).kind();
                    if (kind==ReactorParts.Kind.ROD || kind==ReactorParts.Kind.PIPE) hotNeighbors++;
                }
                absorbed+=(long)settings.absorberRate()*hotNeighbors;
            }
        }
        long ventCooling=(long)vents
                *(maxHeat/Math.max(1,settings.ventRelativeRate())+settings.ventBaseRate());
        return generated>ventCooling+absorbed;
    }

    private static final class Search {
        private final ReactorConfig settings;
        private final ReactorInputs.Fuel fuel;
        private final ItemStack coolant;
        private final Request request;
        private final boolean adjustHeight;
        private final boolean refineHeight;
        private final Budget budget;
        private final Shortlist shortlist;
        private final Set<LayoutKey> seen=new HashSet<>();
        private Design best,danger;

        private Search(ReactorConfig settings,ReactorInputs.Fuel fuel,ItemStack coolant,Request request,
                       Budget budget,boolean adjustHeight,boolean refineHeight,Shortlist shortlist) {
            this.settings=settings;
            this.fuel=fuel;
            this.coolant=coolant;
            this.request=request;
            this.budget=budget;
            this.adjustHeight=adjustHeight;
            this.refineHeight=refineHeight;
            this.shortlist=shortlist;
        }

        boolean exhausted() { return budget.exhausted(); }

        void tryLayout(ReactorLayout layout) {
            if (exhausted()) return;
            int width=layout.width(), length=layout.length();
            var key=LayoutKey.of(layout);
            if (!seen.add(key)) return;
            budget.spend((long)width*length);
            var simulation=new ReactorSimulation(layout,settings,fuel,coolant);
            long perLayer=simulation.generationPerLayer();
            if (perLayer<=0 || request.coolingMode()==CoolingMode.WITH_ICE && !simulation.usesCoolant()) return;
            if (thermallyImpossible(simulation,layout,settings,request)) return;
            if (adjustHeight) {
                int maxHeight=Math.max(1,Math.min(growthLimit(settings.maxSize()),
                        MAX_ASPECT_RATIO*Math.min(width,length)));
                int minHeight=layout.height();
                if (refineHeight)
                    minHeight=Math.max(1,(Math.max(width,length)+MAX_ASPECT_RATIO-1)/MAX_ASPECT_RATIO);
                minHeight=Math.min(minHeight,maxHeight);
                int height=(int)Math.clamp(ceilDiv(request.target(),perLayer),minHeight,maxHeight);
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
            int rodCells=0;
            long fuelPerTick=0;
            for (int z=0;z<length;z++) for (int x=0;x<width;x++) {
                var part=layout.get(x,z);
                if (part.kind()!=ReactorParts.Kind.ROD) continue;
                rodCells++;
                fuelPerTick+=(long)part.rodCount()*height;
            }
            int rodBlocks=rodCells*height;
            if (best!=null && best.exportRate()>=request.target()
                    && (request.target()>0 || export==best.exportRate())
                    && (shapeCost(layout)>shapeCost(best.layout()) || shapeCost(layout)==shapeCost(best.layout())
                    && (fuelPerTick>best.fuelPerTick()
                    || fuelPerTick==best.fuelPerTick() && buildBlocks>best.buildBlocks()))) return;

            simulation.start();
            for (int tick=0;tick<MAX_TICKS && !exhausted();tick++) {
                simulation.tick();
                budget.spend(simulation.totalCells());
                if (simulation.status()!=ReactorSimulation.Status.RUNNING) break;
                if (!request.risk().includesDangerous
                        && (long)simulation.maxHeatSeen()*100
                        > (long)settings.maxHeat()*request.risk().maximumHeatPercent) break;
            }
            var status=simulation.status();
            int[] heat=new int[width*length];
            for (int z=0;z<length;z++) for (int x=0;x<width;x++) {
                var cell=simulation.cell(x,z);
                heat[z*width+x]=cell==null ? 0 : cell.heat;
            }
            var result=new Design(layout,simulation.exportRate(),simulation.maxHeatSeen(),
                    rodBlocks,status,simulation.ticks(),buildBlocks,fuelPerTick,
                    simulation.steadyCoolantPerSecond(),heat);
            if (status==ReactorSimulation.Status.STEADY &&
                    (long)result.peakHeat()*100 <= (long)settings.maxHeat()*request.risk().maximumHeatPercent) {
                if (shortlist!=null) shortlist.offer(result);
                if (better(result,best,request.target())) best=result;
            } else if (request.risk().includesDangerous &&
                    (status==ReactorSimulation.Status.MELTDOWN || status==ReactorSimulation.Status.SAFE_COOLDOWN)) {
                if (better(result,danger,request.target())) danger=result;
            }
        }
    }
}
