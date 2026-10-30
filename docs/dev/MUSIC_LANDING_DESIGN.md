# Music Landing Design

Revision: 2026-10-30. Scope: Home and My Music, desktop and compact layouts.

## Intent

Artwork is the primary visual material. One expressive focus area establishes
hierarchy; the rest of the page is calm, unframed collection content. Avoid a
dashboard of equally prominent pills and duplicate playlist previews.

## Desktop

- Home: compact editorial heading; asymmetric daily selection and now-playing
  focus; direct track previews; recent tracks; discovery collections.
- Daily selection: large title, real cover in a MaterialShapes mask, one
  SplitButton with distinct play and browse actions.
- Now playing: readable title outside artwork, state-driven shape, explicit
  play control, wavy progress only while playing. No whole-card click around
  independent controls.
- My Music: title and a real collection cover stack; six navigation shortcuts;
  one collection toolbar; one lazy playlist grid/list.
- Grid/list and collection modes use connected ToggleButtons. Context menus,
  visible More controls, tooltips and normal keyboard focus remain available.

## Mobile

- Recompose, do not shrink desktop columns.
- Home: daily focus first, three touch shortcuts, three preview tracks, recent
  listening near the top. Promotional banners follow personal content.
- My Music: compact collection heading, 3-column shortcut grid, collection
  modes on their own line and 2-column artwork grid. No duplicate quick grid.
- All independent actions retain a minimum 48dp target. The outer shell owns
  mini-player/navigation clearance.

## Expressive Implementation

Material3 1.12.0-alpha03 is the pinned dependency. Maven Central reports
1.13.0-beta01 as the newest published version when checked on 2026-10-30.
This design does not silently upgrade the application dependency graph.

Actual APIs used through ExpressiveKit: MaterialShapes with Morph, SplitButton,
adaptive ButtonGroup, connected ToggleButton shapes, LoadingIndicator and
LinearWavyProgressIndicator. Theme motion comes from MotionScheme.expressive.
Shapes only morph on meaningful state changes; there is no idle decorative loop.

Primary references:
- https://repo.maven.apache.org/maven2/org/jetbrains/compose/material3/material3/maven-metadata.xml
- https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/ButtonGroup.kt
- https://m3.material.io/styles/shape/overview
- https://m3.material.io/styles/motion/overview

## Acceptance

Render light/dark desktop content widths 640/1060/1600 and compact widths
320/390/600. Include empty/loading/error/local states, long titles and missing
artwork. Verify separate play/browse/More actions and queue-index mapping.
Compile both desktop main modules and inspect JUnit XML, including skip counts.
