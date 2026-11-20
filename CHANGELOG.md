# Changelog

## 0.5.0 — test release

Just Enough Oritech by **NiceClown**.

This is a test release focused on the auto-layout planner. The search now explores more shapes, refines its best result with mixed rod types and alternative cooling rings, and can be cancelled cleanly when you close the screen or start another search.

### Auto layout

- Expanded the rod layout search from a single spacing to anisotropic pitch pairs. The planner now tries dense and sparse arrangements on each axis independently, including `1x2`, `2x1`, `1x3`, `3x1`, `2x3` and `3x2` patterns in addition to the original `2x2`, `3x3` and `4x4` layouts. This lets heat escape along a dense axis while cooling channels remain open on the other.
- Added both rectangle orientations. A `side x (side + 1)` footprint is now searched together with its `(side + 1) x side` transpose instead of only one orientation.
- Added a wider set of candidate rod counts around the estimated ideal: `ideal - 2`, `ideal - 1`, `ideal`, `ideal + 1`, `ideal + 2`, `3/4 ideal`, `5/4 ideal`, the maximum and a single rod group.
- Added a refinement pass after the main search. The best layout is now tested with:
  - every permitted rod type in all rod positions;
  - boundary rods and inner core rods changed to a different rod type;
  - mixed rod layouts: checkerboard mixes and centre/edge splits (for example quad rods in the core and single rods near the walls);
  - the first cooling ring replaced with absorbers, vents, heat pipes or neutron reflectors;
  - a second cooling ring of absorbers or vents behind the heat-pipe ring.
- The refinement pass keeps the best result from every variant, so it can only improve the final candidate within its budget.

### Search reliability and performance

- Replaced the per-footprint budget with separate fixed, growth, refinement and risk budgets, plus one shared wall-clock deadline. A single large reactor can no longer consume the entire search budget before the growth or refinement pass starts.
- Added a dedicated `JustEnoughOritech Auto Design` worker thread. The search no longer runs on the common ForkJoin pool.
- Added real cancellation. Closing the auto-design screen, starting a new search, or replacing the screen now cancels the running search and ignores its late callback.
- Added budget and cancellation checks before candidate construction, inside the cooling loops, and during each simulated tick. The search no longer keeps building and placing full layouts after its budget is already exhausted.
- Protected the UI from stale results with a generation counter: a result from an older request can no longer overwrite a newer one.
- The search now stops early when the budget or deadline is reached and returns the best candidate found so far instead of continuing to build candidates in the background.

### Planner comparison fixes

- When two candidates have the same shape, fuel use, block count, coolant use and peak heat, the planner now prefers the higher export rate. Previously the final tie-breaker preferred the lower export, which could discard a free improvement from extra energy ports.
- The ceiling-port validation now compares the number of ceiling ports against the actual interior ceiling area (`width * length`) instead of the full outer ceiling area, which made the old check unreachable.
- Fuel and rod metrics are now computed from the final layout, so mixed rod types (single, double and quad in one reactor) are compared correctly.

### Compatibility

- Verified with Oritech **2.0.0-exp12** and **2.0.0-exp14**. The reactor classes that define heat, pulses, absorbers, vents, pipes, reflectors and ports are identical in both versions.
- Both exp12 and exp14 are now marked as verified on the client. The previous label only accepted exp12 and could show exp14 as `UNVERIFIED` even when the physics fingerprint matched.
- Updated the README to list Oritech 2.0.0-exp14 as the current verified version.
- The existing physics fingerprint check is unchanged: an unknown Oritech build still shows `UNVERIFIED` or `INCOMPATIBLE` instead of silently using different reactor rules.

### Notes for testers

- Auto layout is still a bounded heuristic, not an exhaustive solver. The refinement pass improves the best candidate but does not guarantee a global optimum.
- The default search can take longer on large reactors now that it explores anisotropic layouts and a refinement pass. The 20-second wall-clock limit and the phase budgets keep it bounded.
- If a safe layout cannot meet the requested RF/t, the planner still keeps the best stable result and, in aggressive mode, can offer a dangerous candidate for review.
- Please report layouts that melt down unexpectedly, especially mixed-rod and second-ring designs, with the reactor dimensions, rod types and the selected risk mode.

## 0.4.2

Just Enough Oritech by **NiceClown**.

- Fixed the English reactor editor labels for decreasing width, length, height, and energy port count.
- Restored the multiplication sign between the core dimensions and the ellipses in the layout search and printer supply status messages.

## 0.4.1

Just Enough Oritech by **NiceClown**. This stable release covers the changes since 0.3.3.

### Reactor editor

- Fixed Shift + mouse wheel panning over the reactor grid, including when the simulator is opened through the Reactor Controller's JEI listing. JEI category navigation still works outside the grid.
- Added a green outline that tracks the editable grid area in both assembly and layer previews. The eye button toggles the outline, and the preview shows the current X and Z ranges.
- Size and energy-port controls step by 1 on a regular click, by 10 with Shift, and by 50 with Ctrl. Ctrl takes priority when both are held.
- Added a temperature legend, expanded tooltips for abbreviated controls, and separated the fuel and coolant counters into fixed rows.

### Projection and 3D printer

- Rotate the world projection in 90-degree steps with R while aiming. Use Y to lock it and N to cancel.
- Import a blueprint by locking the projection to any face of the intended 3D printer, then selecting **Send to printer** in JEI.
- Drag the 3D model in the printer menu to inspect it from different angles. The printer item and menu include usage instructions.

### Compatibility

- Minecraft **26.1.2**, **NeoForge**, and **Java 25**. Requires **Oritech** and **Just Enough Items (JEI)** on the client.
- Verified with **NeoForge 26.1.2.109**, **Oritech 2.0.0-exp12**, and **JEI 29.53.0.115**.
- For multiplayer, install Just Enough Oritech and Oritech on the server and client. Update from 0.3.3 on both sides for printer blueprint compatibility.

Report bugs on [GitHub Issues](https://github.com/NiceClown/JustEnoughOritech/issues).
