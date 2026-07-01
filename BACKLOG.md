# Jeeves — Feature Backlog

## Must Have
_(empty)_

## Should Have
_(empty)_

## Could Have

### Liquid Glass UI
Customisable frosted glass / translucent UI with configurable opacity.

**Approach:**
- Phase 1: Semi-transparent surfaces with opacity slider in settings
- Phase 2: Intra-app backdrop blur (panels blur content behind them)
- Phase 3: Full window transparency with custom frame + OS-level backdrop

**Settings:** opacity (0.05–1.0), blur radius (0–30px), tint colour

**Constraints:** GPU-intensive blur on Intel Iris Xe may need a performance mode; full window transparency requires undecorated window with custom title bar; OS-level see-through needs platform-specific APIs (DwmExtendFrameIntoClientArea on Windows).

---
