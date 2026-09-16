# ForgeKit UI Specification

## Design language

Charcoal-green canvas (`ForgePalette.background` `#0A0D0B`), elevated dark cards
with large corner radii, one ember accent (`ForgePalette.primary` `#FF6B2C`) for
primary actions, amber for warnings, terminal-green for success, uppercase
letter-spaced section headers, monospaced metadata and ids, and status chips for
every lifecycle state. Chakra Petch sets headings and controls; IBM Plex Mono sets
states, identifiers and console output. ForgeKit is dark-only.

`ui/design` is the single source of tokens and components. Screens never re-style
raw Material components; a token change is a one-file change.

| file | contents |
|---|---|
| `ForgeTheme.kt` | `ForgePalette`, `ForgeSpacing`, `ForgeTypography`, `ForgeShapes`, `ForgeTheme` |
| `ForgeSurfaces.kt` | `ForgeBackground`, `ForgeCard` (optional `onClick`), `SectionHeader`, `MetadataLine`, `ForgeDivider`, `MonoText` |
| `ForgeStatus.kt` | `StatusTone`, `toneColor`, `StatusChip`, `ForgeStatusLine` (mono meta line led by the tone-colored state word, pulses in flight), `StatusDot`, `ForgeChoiceChip` |
| `ForgeButtons.kt` | `ForgePrimaryButton`, `ForgeSecondaryButton`, `ForgeDangerButton` |
| `ForgeSwipe.kt` | `ForgeSwipeToDelete` (swipe right to delete a card row) |
| `ForgePage.kt` | `ForgePageHeader`, `ForgeLazyPage` (scrolling), `ForgePage` (fixed, pinned footer), `ForgeActionBar`, `ForgeButtonRow` |
| `ForgeFeedback.kt` | `ForgeEmptyState`, `ForgeLoadingState`, `ForgeProgressBar`, `ForgeNoticeBox` |
| `ForgeSteps.kt` | `ForgeStepList` over presentation-only `ForgeStepVisual` |
| `ForgeLogConsole.kt` | `ForgeLogConsole` over presentation-only `ForgeLogLineVisual` — transparent on the canvas, auto-follows the tail, pauses on drag, and keeps one bottom-right action that shows Copy at the live tail or Go to latest while paused above it |
| `ForgeTextField.kt` | `ForgeTextField`, `forgeTextFieldColors` |
| `ForgeDialog.kt` | `ForgeConfirmDialog`, `rememberConfirmGate` (confirmation before consequential actions) |
| `ForgeToast.kt`, `ForgeInteractionDock.kt` | snackbar presenter; protocol prompt dock |

Rules:

- `ui/design` depends only on `core/common`, so its components take presentation
  models. Domain → visual mapping lives in `app/.../screens/component/`
  (`StatusTones`, `ProvisioningVisuals`, `JobEventVisuals`, `TimeFormat`).
- Lifecycle enums pick tones through exhaustive `when`s (`PluginStatus.tone()`,
  `JobState.tone()`, `RuntimeState.tone()`), never string compares.
- Every page starts with `ForgePageHeader`: overlays pass `onBack`, tab roots may pass
  a `leadingIcon`, and an icon action may go in `trailing`. Headers never carry status
  labels; state is shown in the content (`ForgeStatusLine`).
- Chips show state; they are never buttons.
- Progress uses `ForgeProgressBar` (visible track on cards; `null` = indeterminate).
- Logs of any origin (provisioning, job output, plugin `log` block) use
  `ForgeLogConsole` (no card, no border); size it with `weight(1f)` on a fixed page or
  `ForgeSpacing.consoleEmbeddedHeight` inside a scrolling page. Detail pages link to the
  job log instead of embedding one.

## Interaction lifecycle

- Screens opened from an overlay render the live registry record, so a status change
  (e.g. provisioning → READY) updates the open page and its enabled actions.
- Refused requests (another provisioning run active, missing inputs, plugin with running
  work) toast the reason and leave the user where they are; nothing navigates.
- Run draft → Run opens that job's live log; Back returns to the draft with inputs intact.
- A plugin interface's `progress` and `log` blocks follow the latest run started from it;
  action blocks with `confirm: true` ask first.
- Remove plugin, Clear job history and Repair runtime ask for confirmation. Removing a
  plugin is refused while it has a live provisioning run or unfinished jobs.
- A live dependency install offers no Cancel; finished jobs can be swiped away.

## Declarative plugin UI (`forgekit.ui/v1`)

Plugin screens are JSON trees (`ui/main.json` in a `.forge` package), rendered
by the platform — plugins never draw their own UI. Block kinds:

| block | purpose |
|---|---|
| `static` | read-only text (markdown-free, one paragraph) |
| `input` | binds a control of an action to an input slot |
| `progress` | renders the action's live progress events |
| `log` | renders the action's live protocol log lines |
| `action` | a button that invokes an action (§21 run path) |

`forgekit.ui/v1` is data-driven end to end: adding a block or an action input
requires zero app changes. The renderer composes the same kit components as the
app (`ForgeTextField`, `ForgeProgressBar`, `ForgeLogConsole`).

## App surfaces (v0.1.1)

| surface | content |
|---|---|
| Boot | runtime state machine until READY (state card, health checks as a step list, diagnostics/repair) |
| Home | stat cards (plugins / jobs / imports), import CTA, REGISTRY plugin cards whose meta line leads with the colored state word (ready green, unresolved amber, error red, pulsing blue while provisioning) with live provisioning progress |
| Plugins | search, status filter, plugin cards with the colored state word (tags, runtime) |
| Import | one page, three steps: choose document → review facts → install (see below) |
| Plugin detail | header with back + Open interface (dashboard icon); identity (incl. status), environment, dependencies (live run status card + View job log, or the runtime plan), declared permissions, ACTIONS with Prepare |
| Run draft | back header, INPUTS cards, OPTIONS chip groups, outputs, Run |
| Jobs | job cards (state chip, inline provisioning progress, expandable audit, Logs / Cancel); swipe a finished job right to delete it |
| Job log | header (title + plugin · action), colored state line, progress, output console with channel tags |
| Terminal | real interactive PTY bash — VT100/ANSI renderer, xterm-256color palette, key events incl. arrows/Ctrl-C/CR |
| Settings | runtime facts, diagnostics/repair, storage, observed counts, about |

Navigation: five tabs (home / plugins / jobs / terminal / settings) + overlay flows
(import → plugin detail → run draft, job log). Every screen renders real state
from `ForgeViewModel`; empty states are honest ("No jobs yet — jobs appear
here only after a validated action is explicitly queued").

### Import flow

The page header subtitle names the step (`step 2 of 3 · review facts`). Actions sit in
a pinned action bar so the next step is always visible.

| phase | content | actions |
|---|---|---|
| Choose | picker hero | Select document |
| Inspecting | loading state | — |
| Review | identity, permissions, dependencies, actions, warnings; rejections inline | Approve and install · Choose file · Reject |
| Installing | status card (chip, progress, current step, dependency steps) + provider log console | View job log |
| Installed | same surface, READY or UNRESOLVED outcome | Open plugin · Job log · New import |
| Failed | same surface with the failure reason | Retry install · Job log · Choose file |

The phase is derived (`importflow/ImportPhase.kt`) from the approval request plus live
provisioning state. A refused approval (another run active) stays on Review; a run's
evidence stays on screen after it ends.

## Terminal renderer

`ui/terminal` implements a real VT100/ANSI engine: cell-grid buffer with
scrollback, DEC modes (DECAWM autowrap with pending-wrap quirk, ?25 cursor,
?47/?1047/?1049 alt-screen with xterm-1049 semantics), CUU/CUD/CUF/CUB/CUP/VPA/HPA,
ED/EL, IL/DL, SU/SD, OSC 0/2 title, RIS reset, resize. The Compose Canvas
draws style-run-merged glyph rows and the block cursor from engine state —
it renders exactly what the pty emits.

Color and attributes:

- SGR 16 / bright 16, 256-color (`38;5;n`) and 24-bit (`38;2;r;g;b`), in both `;` and
  ITU `:` sub-parameter forms (`38:2::r:g:b`); bold, dim, italic, underline (`4:0` off),
  strikethrough, reverse, and their resets (22/23/24/27/29).
- `TerminalColors.resolve` is the one style → color decision: bold brightens colors 0–7,
  dim draws at 60% alpha, reverse swaps foreground/background (defaults included).
- `TerminalTheme`: ForgeKit-hued base 16, xterm-exact 6×6×6 cube (0/95/135/175/215/255)
  and grayscale ramp.

Interactive shell:

- The terminal starts bash with `--rcfile` ForgeKit's `InteractiveShellProfile`
  (`files/app/shell/forgekit.bashrc`, regenerated when it changes). Load order:
  Termux `$PREFIX/etc/bash.bashrc` → ForgeKit profile → `~/.bashrc` (user settings win).
- The profile sets the prompt (ember working directory, grey `$`) and `--color=auto`
  aliases for `ls`, `grep`, `egrep`, `fgrep`, `diff`; the session exports
  `COLORTERM=truecolor` and `CLICOLOR=1`. Plugin jobs never use this profile.
- Job, provisioning and plugin `log` consoles show plain text: escape sequences are
  stripped (`stripAnsi`) rather than rendered.
