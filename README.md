# Just Enough Oritech

JEI multiblock editor and client-side simulator for Oritech's nuclear reactor.

Created by **NiceClown**. See the [changelog](CHANGELOG.md) for release notes and [GitHub Issues](https://github.com/NiceClown/JustEnoughOritech/issues) for bug reports.

## Supported environment

- Minecraft 26.1.2
- NeoForge 26.1.2.109
- JEI 29.53.0.115
- Oritech 2.0.0-exp14 (verified with 2.0.0-exp12 and 2.0.0-exp14)

Both client and server need this addon. At login, and again after a datapack reload, the server sends Oritech's active reactor settings, fuel recipes, and coolant tag members. Client-side config values are not used. An unknown Oritech version can run with an **UNVERIFIED** label when its required blocks, config fields, recipes, and coolant tag remain available. Missing capabilities block simulation and show the last verified version.

## JEI controls

- Press **K** in a world, or open the recipe or uses of the Nuclear Reactor Controller in JEI.
- Left click in the grid to place a component. Right click to clear the cell. Scroll over the grid to pan vertically, or hold Shift to pan sideways. Width, length, height, and energy port controls step by 1 on a regular click, 10 with Shift, and 50 with Ctrl. The vertical stack repeats this grid.
- Switch between the complete assembly and a layer. The preview renders Oritech's block models. The green outline marks the area currently shown in the editor. The eye button toggles it. The heat legend gives thresholds as a share of the configured temperature limit. Hover over abbreviated controls for their full descriptions. The block list has a scrollbar.
- **Auto layout** tests mirror-symmetric arrangements using direct cooling, shared heat pipes and neutron reflectors. The search checks anisotropic rod spacing and a wider range of rod counts; on non-square footprints both transposed channel orientations are tried, while on a square footprint the planner skips the transposed pitch pairs to conserve search budget. A bounded heuristic with a 20-second wall-clock limit and per-phase work budgets keeps a small, diversified Top-K shortlist and refines each shortlisted candidate with mixed rod types, alternate cooling rings, atomic pipe-ring upgrades and local moves. Enter an export target in RF/t or leave it blank to maximize export at the current dimensions, select the permitted rod types, and choose a risk level. With a target and resizing enabled, the search can change all three dimensions when the original size falls short or is too stretched. It favors compact cores, followed by fuel use and block count, among candidates that meet the target. Resized cores have an aspect ratio of at most 2:1 and respect the lower of the server limit and 66 blocks per axis. Proposed dimensions apply when you accept the design. The conservative mode keeps peak heat at or below 65% of the limit, and balanced mode permits stable layouts up to the limit. Aggressive mode prioritizes stable layouts and can offer a stronger candidate with observed meltdown or safety shutdown. Applying that candidate requires confirmation. If the requested export is unattainable, the best stable result the search found remains available.
- **Cooling and export**. Auto compares passive, active and mixed cooling. No ice uses passive cooling. With ice includes heat absorbers and reports the sustained coolant consumption at 20 TPS. The simulator can verify a repeating temperature and coolant cycle. The number of energy ports is limited by the space on the side walls and each port uses the server's Oritech transfer rate. The reported export assumes enough downstream capacity to accept it.
- **Preview** shows block outlines in the world. While aiming, press **R** to turn the build around the aimed corner. Press **Y** to lock it or **N** to cancel. When aiming at an underside, the upright build hangs below that face. Large builds use a bounding outline.
- Select Russian in Minecraft's language settings to use the Russian interface.

## Reactor 3D printer

Craft one from Oritech steel, motors, a tier-three machine core, a processing unit, and a dispenser. It is also available in the Redstone creative tab. Start Preview in JEI, aim at any face of the desired printer and lock the projection. Return to JEI and send the layout to that printer. Open its menu to drag and rotate the 3D model, move the build along X, Y and Z before printing starts, show the world projection or cancel the job. The printer item and the menu's help icon show these steps.

The printer builds while powered by redstone and its owner is within 32 blocks. It uses adjacent item storage first, then the owner's main inventory. Matching blocks already in place are skipped. An obstruction or unavailable material pauses the job. Switching off redstone pauses it, and cancelling keeps the blocks already placed. The maximum printable interior is 66 blocks along each axis, subject to Oritech's own size limit. The printer saves its job with the world. Its menu lists the blocks still missing after counting extractable adjacent items and the nearby owner's inventory. The projection outlines the first obstructing block in red.

## Build

Run `./gradlew build` (`.\gradlew.bat build` on Windows) with Java 25 available. The artifact is written to `build/libs/`.
