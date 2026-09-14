## Declarative UI contract

The optional UI document is a strict JSON document with a flat, top-to-bottom block list. The manifest owns action/input/option contracts; the UI document only chooses presentation and wiring.

```json
{
  "schema": "forgekit.ui/v1",
  "title": "Hello",
  "description": "Enter a name and run.",
  "blocks": [
    { "type": "static", "id": "intro", "text": "This runs locally." },
    { "type": "input", "id": "name-field", "action": "greet", "input": "name", "label": "Name", "helper": null },
    { "type": "option", "id": "style-choice", "action": "greet", "option": "style", "label": "Style" },
    { "type": "progress", "id": "run-progress" },
    { "type": "log", "id": "run-log" },
    { "type": "action", "id": "run", "invoke": "greet", "label": "Greet", "confirm": false }
  ]
}
```

Unknown keys and unknown block `type` values are rejected by the full UI parser. IDs must be unique and follow the lowercase action-ID pattern. An input/option/action reference must exist in the manifest. Every required input of an invoked action must have a corresponding input block.

### Blocks and actual rendering

| Block | Current behavior |
|---|---|
| `static` | Requires `text` or `markdown`. The selected string is displayed as plain text; Markdown syntax is not rendered. If both exist, `text` wins. |
| `input` | Uses the manifest input type. Text/password/number use a text field; password is visually masked. Select uses choice chips. Checkbox/switch use a switch. Slider is fixed to `0..100` and defaults visually to `50`. File and directory launch the host picker. Date/time are plain text fields with format hints. |
| `option` | Renders the option choices as chips. Effective value is explicit value, then declared default, then first choice. |
| `action` | Calls the host with the manifest action ID only after UI-state validation. |
| `progress` | Reserved live progress surface. The current installed-UI host does not connect job progress, so it renders nothing. |
| `log` | Reserved live log surface. The current installed-UI host does not connect job logs, so it renders nothing. |

The `helper` input hint and `confirm` action flag are parsed but not currently rendered/enforced. `date` and `time` formats are not validated. The declarative renderer validates numeric input and select membership before invoking; the generic action-draft screen currently applies only required/non-blank validation. Plugin code must validate all inputs again.

### File and directory values

The host uses Android’s `GetContent` picker, copies the selected content into the app cache, and sends the resulting absolute cache path as a string. The copy is temporary app data; do not assume it survives cache cleanup or app reinstall.

The declared `directory` type currently launches the same content picker as `file`; there is no directory-tree bridge. Treat directory selection as unsupported in `0.1.0`.

### UI state and action payload

Input and option state is stored as strings. The invoke payload contains:

- each rendered manifest input ID mapped to its current string (an optional untouched input becomes an empty string);
- each rendered option ID mapped to explicit value, default, or first choice.

An input declared in the manifest but omitted from the UI is absent unless it is required, in which case an action block invoking that action fails full UI validation.

### Import-time limitation

Package validation checks that the declared UI entry exists, parses as JSON, and has the correct schema string. Cross-references and unknown nested fields are checked later by `UiDocumentParser` when the installed interface opens. A package can therefore pass import and still have an interface rejected at open time. Always exercise the interface after validation; do not equate a valid package verdict with a fully valid UI document yet.
