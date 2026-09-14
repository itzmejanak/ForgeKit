# Golden plugin examples

These source trees are executable acceptance fixtures, not illustrative pseudocode.
The integration suite builds them with `:tools:forge-builder`, imports them through
the production validator/installer, and runs their real processes through the
`forgekit/1` job protocol.

- `hello/` covers manifest, declarative UI, progress/log/result, and input failure.
- `interactive/` covers a password prompt and host `prompt_response` round trip.
- `ssl-patcher/` covers a file input, option, real ZIP inspection, and file output.

Keep these aligned with `Docs/developer/` and the integration tests.
