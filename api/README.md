# Module api

Internal API of the Shadowzap kernel (packages `io.github.trickhook.shadowzap.api.*`), kept in its own module so the
surface features build on stays small and explicit.

- `hooks`: method and constructor hooking independent of the Xposed flavour, plus reflective lookup helpers.
- `host`: the host scope every feature runs in (environment, lifecycle, logging, coroutines).
- `version`: lenient version parsing and ranges.
