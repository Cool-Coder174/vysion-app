---
version: 1
slug: "com-vysion-xr-platform-ui-spatialshell-kt-30f182c1"
primary_target: "app/src/main/kotlin/com/vysion/xr/platform/ui/SpatialShell.kt"
related_targets: ["app/src/main/kotlin/com/vysion/xr/platform/ui/FlatShell.kt"]
---

# Surface: Vysion XR headset + phone UI

Scope: the Galaxy XR spatial UI (onboarding, status window with mode rail and toolbar, outlines in passthrough, diagnostics window) and the S25 Ultra gesture-first phone app. Mode: **Operate**. The wearer completes a task (read, describe, find a door) mostly by voice, often eyes-closed; the panel is the visual twin of what is spoken.

Authority: Michelle Bui's approved comps (23 pages, Oct 2026), rendered under `.impeccable/mocks/approved/page-NN.png` (gitignored: bystanders in frame). Build is code-led on native; the comps are the critique reference.

Constraints: system font; spoken text only from `voice/Cues.kt`; numbers only if measured ("not measured" otherwise); distances in the unit Cues speaks (meters, SRS) until the team decides otherwise; targets ≥ 56 dp in XR, ≥ 48 dp on phone; answer text ≥ 48 sp at ≥ 7:1.

## Direction contract

THESIS: One calm glass window that always says what Vysion is doing right now, in words big enough to read across a room, with the answer's spoken phrase lit in yellow. Refuses the dashboard of buttons: one status, one contextual toolbar, modes on a quiet rail.

OWN-WORLD: Smoked neutral glass over passthrough (graphite at ~80%, hairline light border, soft top sheen); onboarding on deep indigo-violet aurora. White pill primary, graphite pill secondary, red Stop, blue talk. A small luminous blue orb is Vysion's presence. Meaning colors, one job each: yellow read/describe and the spoken phrase, cyan navigation, green arrived/done, orange warning, red error/stop, purple thinking. Outlines in passthrough share those colors with a solid label chip; dashed when uncertain.

STORY: The wearer always knows the state (Ready → Listening → Thinking → answer), hears it and sees it, and can stop anything from anywhere.

FIRST VIEWPORT: Left: vertical glass rail (Aa, eye, arrow, more) with the active mode as a white disc and a gaze label. Center: status window, orb + state title + subtitle, then the answer at display size with the spoken phrase in yellow. Bottom edge: toolbar pills, Stop first.

FORM: Spatial status window with edge orbiters; position 1 of 1 (pinned by approved comps); seed key: approved-comps-michelle-bui-2026-10.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
