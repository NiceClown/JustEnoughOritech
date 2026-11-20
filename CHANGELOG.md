# Changelog

## 0.5.2 — test fix release

Just Enough Oritech by **NiceClown**.

This release fixes the second planner review (the v0.5.1 findings) and adds regressions for the refined cooling and resize paths. 0.5.2 supersedes 0.5.1.

### Top-K refinement

- **Every refinement seed is independent.** Each seed now gets a fresh search with `best = null` and `danger = null`. The seed is first simulated with its own height policy, then `refineBest` mutates that seed's local winner. Previously the global winner was injected as the baseline, so after the first mutation seeds #2–#5 were silently refining seed #1 again.
- Results from every seed are merged into the global winner with the normal `better(...)` comparator for both stable and dangerous candidates.
- **Refinement budget is a pool.** A single `REFINE_BUDGET` is split through `child()` / `absorb()`, so a fast seed returns its unused budget to the next seed. The loop also stops when the pool reports the deadline or cancellation.

### Cooling and search

- **One-absorber-side patterns fixed.** Absorbers are now placed perpendicular to the pipe channels. With `verticalChannels == true` pipes run on Z and absorbers on X; with `false` pipes run on X and absorbers on Z. The previous argument placed both on the same axis, so no absorber was ever placed and the pattern degenerated to a passive vent-only layout.
- **Stretched initial layouts skip the fixed phase.** When resizing is enabled and the starting reactor is not balanced, the fixed search is skipped entirely. This saves the fixed budget and keeps stretched candidates out of the top-K shortlist, so they cannot win later on raw export.
- **Goal-driven local search.** The hard “75% heat” split was replaced with an optimisation goal:
  - when more power, a target shortfall or resizing is in play, cold rod groups are upgraded, reflectors are added and combined “upgrade + adjacent vent/absorber” moves are tried while the peak is below the risk limit;
  - when the heat ceiling is reached, pipes/reflectors are replaced with vents or absorbers to create headroom;
  - when a positive target is already exceeded, the hottest groups are downgraded and spare reflectors are removed to save fuel, blocks and heat.
- Removed the dead `EMPTY → vent/absorber` local moves. Cooling upgrades now replace `PIPE` or `REFLECTOR` cells, or are combined with a rod upgrade, so they do not lose immediately on block count.
- Combined moves allow the extra cooler to be paid for by a higher per-layer output or a lower refined height.

### Deduplication and height

- The `LayoutKey` set is shared between the fixed and growth phases, and a separate set is shared by all top-K refinement seeds.
- Duplicate checks now happen twice: an early 2D check before simulation, and a final key check after height adjustment. Seeds with different starting heights that converge to the same final layout are skipped correctly.
- Added a comment documenting the deliberate transposed-layout canonicalisation trade-off: rotations are deduplicated for speed, but the simulation's `HashMap` iteration order can differ by a few edge ticks.

### Tests

- Added `oneAbsorberSidePlacesAbsorbersPerpendicularToPipes`, checking both channel orientations.
- Added `resizesStretchedLayoutToBalancedShape`, which starts from a 2×10×10 reactor and verifies a balanced result within the 2:1 limit.
- 13 planner/simulation tests now pass, including 10, 100, 2,000, 10,000 and 1,000,000 RF/t targets.

### Compatibility

- Minecraft **26.1.2**, NeoForge **26.1.2.109**, Java **25**.
- Verified with Oritech **2.0.0-exp12** and **2.0.0-exp14**.
- Client and server both need the mod.

## 0.5.1 — test fix release

Just Enough Oritech by **NiceClown**.

This test release fixes the issues found in the 0.5.0 planner review. It focuses on correctness, search quality and the stability detector. 0.5.1 supersedes 0.5.0; the first test release is kept for history.

### Reactor simulation

- **Unified stability detector.** Removed the separate passive `steadyTicks >= 200` branch. Passive reactors now use the same full-state cycle detector as reactors with coolant. A fixed point is accepted after 1–2 ticks, and a short safe oscillation (for example a 2-tick vent cycle) is accepted after 2–4 ticks instead of burning the full 3,600-tick limit and being discarded.
- **Risk-aware early stop.** For cautious and balanced modes, the simulation stops as soon as the observed peak heat exceeds the selected risk threshold (65% or 100% of the configured limit). Such a design can never pass the final risk check, so the remaining ticks were wasted.
- **O(1) thermal pre-check.** Before the first tick, the designer now rejects a candidate when:
  - any rod has no adjacent heat pipe, vent or absorber; or
  - the total generated heat per tick is greater than the maximum possible cooling at the selected risk ceiling. The bound counts vent capacity at the ceiling temperature and absorber contacts with rods and heat pipes.
- **Corrected passive coolant value.** Passive designs now report `0.0` coolant per second explicitly, without relying on the removed passive branch.
- **Regression tests.** Added a simulation test that verifies a passive fixed point reaches `STEADY` within a few ticks.

### Auto-layout search

- **Fixed boundary detection.** `replaceRodRing` now uses the bounding box of all rod cells (`minX`, `maxX`, `minZ`, `maxZ`) instead of requiring four rod neighbours. With the auto-layout pitches that condition was impossible, so every rod was treated as a boundary rod and the core pass was dead code.
- **Fixed checkerboard mixing.** Mixed rod types now use the ordinal indices of the unique rod X and Z coordinates, not the world-coordinate parity `(x + z) & 1`. The old code degenerated to a single rod type on odd reactor sizes with even pitches.
- **Atomic pipe-ring refinement.** The refinement pass now builds and tests “first ring = heat pipe, second ring = vent/absorber” as one layout. The intermediate pipe-only layout is no longer simulated and can no longer prevent the second ring from being created. Second-ring cells may also be replaced when they already contain a vent or absorber.
- **Orientation-aware cooling.** Pitch pairs now carry a `verticalChannels` flag. Reflectors, pipes, outer reflectors, outer absorbers and the pipe/reflector axes all swap consistently for transposed pitches such as `{2,1}`, `{3,1}` and `{3,2}`. The previous code always used X for reflectors and Z for pipes, which blocked the only cooling channel on transposed layouts.
- **Centre-line cooling.** Odd-sized reactors now place outer reflectors and absorbers on both sides of a centre-line rod when the cells are empty. Previously `outward == 0` compared the rod with itself, so the centre line always stayed uncooled.
- **Height refinement.** The refinement search now receives the resize state and can lower the reactor height when stronger rods, reflectors or a better cooling ring increase generation per layer. It respects the balanced minimum height and the 2:1 aspect limit instead of keeping the old, unnecessarily tall shape.
- **Candidate deduplication.** Every search phase keeps a canonical `LayoutKey` (dimensions, height and ordered part IDs). Exact duplicates and transposed duplicates are skipped before a new simulation is constructed. Footprints no longer generate both `(side, side + 1)` and `(side + 1, side)`.
- **Per-footprint growth quota.** The growth budget is divided between the remaining footprints, with unused budget returned to the pool. A single large footprint can no longer consume the entire growth phase before the anisotropic or neighbouring sizes are tried.
- **Top-K refinement seeds.** The main search now keeps a small diversified shortlist (up to five candidates, keyed by shape, cooling composition and rod types). Each seed receives a share of the refinement budget, so a candidate that lost on homogeneous rods can still win after a mixed-rod or cooling-ring upgrade.
- **Temperature-guided local search.** The current winner is refined with up to 24 symmetric hill-climb steps using the actual per-cell temperatures:
  - with temperature headroom, the coldest rod groups are upgraded to a stronger rod type or get an adjacent neutron reflector;
  - near the heat limit, the hottest groups are downgraded or get an adjacent vent/absorber in place of an empty, pipe or reflector cell.
- **Corrected mixed-rod metrics.** Fuel demand and rod-block counts are calculated from the final layout, so mixed single/double/quad layouts are compared correctly.

### Build and tests

- Added eleven planner/simulation regression tests covering stable design search, 10, 100, 2,000, 10,000 and 1,000,000 RF/t targets, cautious heat limits, wide layouts, cancellation, missing cooling components and fast passive stabilisation. All tests pass; the 1,000,000 RF/t target is covered by a 15x15x15 result in the current test fixture.
- The build now has a test source set wired to the main NeoForge classpath and JUnit 5.

### Compatibility

- Minecraft **26.1.2**, NeoForge **26.1.2.109**, Java **25**.
- Verified with Oritech **2.0.0-exp12** and **2.0.0-exp14**; the reactor physics classes are identical in both builds.
- Client and server both need the mod.

### Notes

- Auto layout is still a bounded heuristic, not an exhaustive solver. The new search and local refinement improve the result but do not guarantee a mathematical global optimum.
- The search is capped by phase budgets and a 20-second wall-clock deadline. On very large reactors it returns the best candidate found so far.
- Please report a design that melts down unexpectedly together with its dimensions, rod types and selected risk mode.

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
