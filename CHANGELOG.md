# Changelog

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
