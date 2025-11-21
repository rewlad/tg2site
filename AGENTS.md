## Automation Guidelines

- Docker builds push images tagged as `ghcr.io/<owner>/<repo>:<commit-sha>`, keeping tags tied to the build commit so automation stays deterministic.
- Runtime containers drop privileges to `appuser` (UID/GID 1000); avoid assuming root and adjust IDs in the `Dockerfile` if mounts require different ownership.

## Telegram Notes

- Bot API libraries (python-telegram-bot, Aiogram, botogram) poll `getUpdates` by default and also support webhooks for push delivery.
- Telethon and Pyrogram rely on the MTProto protocol over persistent TCP connections instead of the Bot API.
- `getUpdates` supports long polling via the `timeout` parameter (up to ~50 seconds) to minimize request churn.
- Telegram keeps one update queue per bot token; consuming an update by advancing the offset removes it from the queue.
- Updates expire after roughly a day if the bot stays offline without acknowledging them.

## Agent Style Guide

- Communication
  - Separate clarifying questions from implementation actions during dialogue.
  - Keep responses concise; favour the simplest viable approach (KISS).
  - Assume a modern container runtime; default to crash-and-restart behaviour when workloads are lightweight.

- TypeScript Preferences
  - Write semicolon-free code with minimal branching; favour linear happy paths.
  - Replace small `if` statements with ternaries when it improves readability.
  - Simplify functions aggressively: remove auxiliary branches when they do not change behaviour.
  - Avoid mutability; prefer `const`, keep any required mutable structures shallow and explicitly named, and do not introduce `let` unless performance demands it.

- Error Handling
  - Fail fast via the shared `die` helper; embed it in expressions instead of calling `throw` directly.
  - Avoid catching errors in lower layers; allow supervisors (systemd, Kubernetes) to restart lightweight agents.
  - For service-style agents (components that answer end users, such as HTTP handlers), catch only at the outer boundary to send a controlled error response without leaking internal details.
