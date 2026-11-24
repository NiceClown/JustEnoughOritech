package com.justenoughoritech.sim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

public final class ReactorLayout {
    private int width = 3;
    private int length = 3;
    private int height = 3;
    private int energyPorts = 1;
    private ReactorParts.Part[] cells = new ReactorParts.Part[9];

    public ReactorLayout() { Arrays.fill(cells, ReactorParts.Part.EMPTY); }
    public int width() { return width; }
    public int length() { return length; }
    public int height() { return height; }
    public int energyPorts() { return energyPorts; }
    public void setEnergyPorts(int count) { energyPorts = Math.clamp(count, 1, maxEnergyPorts(width,length,height)); }
    public static int maxEnergyPorts(int width,int length,int height) {
        if (width<1 || length<1 || height<1) return 0;
        return (int)Math.min(Integer.MAX_VALUE,2L*(width+(long)length)*height-1);
    }
    public ReactorParts.Part get(int x, int z) { return cells[z * width + x]; }
    public void set(int x, int z, ReactorParts.Part part) {
        cells[z * width + x] = part == null ? ReactorParts.Part.EMPTY : part;
    }
    public void setHeight(int height, int max) {
        this.height = Math.max(1, Math.min(max, height));
        setEnergyPorts(energyPorts);
    }

    public void resize(int newWidth, int newLength, int max) {
        newWidth = Math.max(1, Math.min(max, newWidth));
        newLength = Math.max(1, Math.min(max, newLength));
        var next = new ReactorParts.Part[newWidth * newLength];
        Arrays.fill(next, ReactorParts.Part.EMPTY);
        for (int z = 0; z < Math.min(length, newLength); z++)
            for (int x = 0; x < Math.min(width, newWidth); x++) next[z * newWidth + x] = get(x, z);
        width = newWidth;
        length = newLength;
        cells = next;
        setEnergyPorts(energyPorts);
    }

    /** Repeat the entire core on the horizontal plane. Height and power ports stay as set. */
    public boolean tile(int xCopies, int zCopies, int max) {
        if (xCopies < 1 || zCopies < 1 || xCopies > max / width || zCopies > max / length) return false;
        int newWidth = width * xCopies;
        int newLength = length * zCopies;
        var tiled = new ReactorParts.Part[newWidth * newLength];
        for (int z = 0; z < newLength; z++) {
            int source = (z % length) * width;
            for (int x = 0; x < xCopies; x++) {
                System.arraycopy(cells, source, tiled, z * newWidth + x * width, width);
            }
        }
        width = newWidth;
        length = newLength;
        cells = tiled;
        return true;
    }

    public ReactorLayout copy() {
        var copy = new ReactorLayout();
        copy.replaceWith(this);
        return copy;
    }

    public void replaceWith(ReactorLayout other) {
        width = other.width;
        length = other.length;
        height = other.height;
        energyPorts = other.energyPorts;
        cells = other.cells.clone();
    }

    public static final String WALL = "oritech:reactor_wall";
    public static final String CONTROLLER = "oritech:nuclear_reactor_controller";
    public static final String FUEL_PORT = "oritech:reactor_fuel_port";
    public static final String COOLANT_PORT = "oritech:reactor_coolant_absorber_port";
    public static final String ENERGY_PORT = "oritech:reactor_energy_port";

    public record Placement(int x, int y, int z, String blockId) {}

    public record Orientation(Rotation rotation, Mirror mirror) {
        public Orientation {
            rotation = rotation == null ? Rotation.NONE : rotation;
            mirror = canonicalMirror(mirror);
        }

        public static Mirror canonicalMirror(Mirror mirror) {
            return mirror == Mirror.FRONT_BACK ? Mirror.FRONT_BACK : Mirror.NONE;
        }

        public Orientation rotated(int steps) {
            int count = Math.floorMod(steps, 4);
            Rotation next = rotation;
            for (int i = 0; i < count; i++) next = next.getRotated(Rotation.CLOCKWISE_90);
            return new Orientation(next, mirror);
        }

        public Orientation flippedWorldX() {
            Mirror flipped = mirror == Mirror.FRONT_BACK ? Mirror.NONE : Mirror.FRONT_BACK;
            Rotation next = switch (rotation) {
                case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
                case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
                default -> rotation;
            };
            return new Orientation(next, flipped);
        }

        public Orientation flippedWorldZ() {
            Mirror flipped = mirror == Mirror.FRONT_BACK ? Mirror.NONE : Mirror.FRONT_BACK;
            Rotation next = switch (rotation) {
                case NONE -> Rotation.CLOCKWISE_180;
                case CLOCKWISE_180 -> Rotation.NONE;
                default -> rotation;
            };
            return new Orientation(next, flipped);
        }
    }

    public int buildWidth(Rotation rotation) {
        return rotation.rotate(Direction.EAST).getAxis()==Direction.Axis.X ? width : length;
    }

    public int buildLength(Rotation rotation) {
        return rotation.rotate(Direction.SOUTH).getAxis()==Direction.Axis.Z ? length : width;
    }

    /** Keep the aimed corner fixed while finding the minimum world coordinates of the rotated build. */
    public BlockPos buildOrigin(BlockPos corner,Direction face,Rotation rotation) {
        int y=face==Direction.DOWN ? -height-1 : 0;
        return switch (rotation) {
            case NONE -> corner.offset(0,y,0);
            case CLOCKWISE_90 -> corner.offset(-length-1,y,0);
            case CLOCKWISE_180 -> corner.offset(-width-1,y,-length-1);
            case COUNTERCLOCKWISE_90 -> corner.offset(0,y,-width-1);
        };
    }

    /** Center the outer shell flush against the selected face of a printer block. */
    public BlockPos faceOrigin(BlockPos printerPos, Direction face, Rotation rotation) {
        int outerW = buildWidth(rotation) + 2;
        int outerH = height + 2;
        int outerL = buildLength(rotation) + 2;
        int halfW = outerW / 2;
        int halfL = outerL / 2;
        return switch (face == null ? Direction.UP : face) {
            case UP -> printerPos.offset(-halfW, 1, -halfL);
            case DOWN -> printerPos.offset(-halfW, -outerH, -halfL);
            case NORTH -> printerPos.offset(-halfW, 0, -outerL);
            case SOUTH -> printerPos.offset(-halfW, 0, 1);
            case WEST -> printerPos.offset(-outerW, 0, -halfL);
            case EAST -> printerPos.offset(1, 0, -halfL);
        };
    }

    public Direction controllerFacing(Rotation rotation, Mirror mirror) {
        int rotatedWidth = buildWidth(rotation);
        for (var block : placements(rotation, mirror)) {
            if (CONTROLLER.equals(block.blockId())) return wallFacing(block, rotatedWidth);
        }
        return Direction.WEST;
    }

    public List<Placement> placements(Rotation rotation) {
        return placements(rotation, Mirror.NONE);
    }

    public List<Placement> placements(Rotation rotation, Mirror mirror) {
        var base = placements();
        if (rotation == Rotation.NONE && mirror == Mirror.NONE) return base;
        // Preserve the build order across transforms so obstruction checks and printing agree.
        return base.stream().map(block -> {
            int mx = mirror == Mirror.FRONT_BACK ? width + 1 - block.x() : block.x();
            int mz = mirror == Mirror.LEFT_RIGHT ? length + 1 - block.z() : block.z();
            return switch (rotation) {
                case CLOCKWISE_90 -> new Placement(length + 1 - mz, block.y(), mx, block.blockId());
                case CLOCKWISE_180 -> new Placement(width + 1 - mx, block.y(), length + 1 - mz, block.blockId());
                case COUNTERCLOCKWISE_90 -> new Placement(mz, block.y(), width + 1 - mx, block.blockId());
                case NONE -> new Placement(mx, block.y(), mz, block.blockId());
            };
        }).toList();
    }

    public static boolean onShell(Placement block,int width,int height,int length) {
        return block.x()==0 || block.x()==width+1 || block.y()==0 || block.y()==height+1
                || block.z()==0 || block.z()==length+1;
    }

    public static Direction wallFacing(Placement block,int width) {
        return block.x()==0 ? Direction.WEST : block.x()==width+1 ? Direction.EAST
                : block.z()==0 ? Direction.NORTH : Direction.SOUTH;
    }

    /** The origin is the outside bottom corner with the smallest X and Z. */
    public List<Placement> placements() {
        var blocks = new java.util.ArrayList<Placement>();
        int ports = energyPorts;
        for (int y = 0; y <= height + 1; y++) {
            for (int z = 0; z <= length + 1; z++) {
                for (int x = 0; x <= width + 1; x++) {
                    boolean insideX = x > 0 && x <= width;
                    boolean insideZ = z > 0 && z <= length;
                    boolean insideY = y > 0 && y <= height;
                    String id = WALL;
                    if (insideX && insideZ && insideY) {
                        var part = get(x - 1, z - 1);
                        if (part.kind() == ReactorParts.Kind.EMPTY) continue;
                        id = part.id();
                    } else if (insideX && insideZ && y == height + 1) {
                        id = switch (get(x - 1, z - 1).kind()) {
                            case ROD -> FUEL_PORT;
                            case ABSORBER -> COOLANT_PORT;
                            default -> WALL;
                        };
                    } else if (insideY && (insideX != insideZ)) {
                        if (x == 0 && y == 1 && z == 1) id = CONTROLLER;
                        else if (ports > 0) {
                            id = ENERGY_PORT;
                            ports--;
                        }
                    }
                    blocks.add(new Placement(x, y, z, id));
                }
            }
        }
        return List.copyOf(blocks);
    }

    public void clear() { Arrays.fill(cells, ReactorParts.Part.EMPTY); }

    public List<String> validationErrors(int max) {
        var errors = new java.util.ArrayList<String>();
        int rods=0, topPorts=0;
        for (var part : cells) {
            if (part.kind()==ReactorParts.Kind.ROD) { rods++; topPorts++; }
            if (part.kind()==ReactorParts.Kind.ABSORBER) topPorts++;
        }
        if (width>max || length>max || height>max) errors.add("gui.just_enough_oritech.dimensions");
        if (rods==0) errors.add("gui.just_enough_oritech.need_rod");
        if (energyPorts<1) errors.add("gui.just_enough_oritech.need_energy");
        if (energyPorts>maxEnergyPorts(width,length,height)) errors.add("gui.just_enough_oritech.port_space");
        if (topPorts > width*length) errors.add("gui.just_enough_oritech.ceiling_space");
        return List.copyOf(errors);
    }

    /** Block counts for the repeated core, outer shell, ceiling ports, controller and energy ports. */
    public Map<String,Integer> blockCounts() {
        var counts = new LinkedHashMap<String,Integer>();
        int ceilingPorts=0;
        for (var part : cells) {
            if (part.kind()==ReactorParts.Kind.EMPTY) continue;
            counts.merge(part.id(),height,Integer::sum);
            if (part.kind()==ReactorParts.Kind.ROD) {
                counts.merge("oritech:reactor_fuel_port",1,Integer::sum);
                ceilingPorts++;
            } else if (part.kind()==ReactorParts.Kind.ABSORBER) {
                counts.merge("oritech:reactor_coolant_absorber_port",1,Integer::sum);
                ceilingPorts++;
            }
        }
        int outerW=width+2, outerL=length+2, outerH=height+2;
        int shell=outerW*outerL*outerH-width*length*height;
        int walls=Math.max(0,shell-ceilingPorts-1-energyPorts);
        counts.merge("oritech:reactor_wall",walls,Integer::sum);
        counts.merge("oritech:nuclear_reactor_controller",1,Integer::sum);
        counts.merge("oritech:reactor_energy_port",energyPorts,Integer::sum);
        return Map.copyOf(counts);
    }
}
