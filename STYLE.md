## tg2site Style Guide

- **Imports**: Use `from module import name1, name2` per module to keep namespace focused (`bot.py:1-5`).
- **Config loading**: Parse JSON config and secrets separately, relying on `Path(...).read_bytes().decode()` for files (`bot.py:15-24`).
- **Fail-fast guards**: Validate each required value with `value or never(ValueError(...))`, defining `never` as the shared raiser (`bot.py:8-9`, `bot.py:15-24`).
- **Logging**: Initialize with `basicConfig`, selecting the level from `TG2SITE_LOG_LEVEL`, and log via imported `info`/`warning` helpers without extra formatting (`bot.py:2`, `bot.py:13`, `bot.py:26-27`).
- **Structure**: Keep `main` straightforward, return an exit code, and invoke it directly under the module guard (`bot.py:12-33`).
- **Classes**: Prefer module-level helpers; define methods only when behaviour genuinely needs stateful encapsulation.
- **Collections**: Use comprehensions for derived iterables instead of loops with `append`/`extend`/mutation; when listing constant values, construct them directly without comprehensions.
- **Scope**: Tiny services can live entirely in `main`; define nested helpers inside `main` when they need captured configuration instead of wiring global state.
- **Exceptions**: Inside helper functions, raise underlying exceptions directly—avoid wrapping or converting unless there is a clear boundary.
- **Construction**: Keep literal structures on one line when they fit; rely on `dumps(..., sort_keys=True)` for deterministic output, and prefer `write_bytes`/`read_bytes` paired with explicit `encode`/`decode`.

