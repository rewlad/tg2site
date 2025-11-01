## Automation Guidelines

- Docker builds push images tagged as `ghcr.io/<owner>/<repo>:<commit-sha>`, keeping tags tied to the build commit so automation stays deterministic.
- Runtime containers drop privileges to `appuser` (UID/GID 1000); avoid assuming root and adjust IDs in the `Dockerfile` if mounts require different ownership.

## Telegram Notes

- Bot API libraries (python-telegram-bot, Aiogram, botogram) poll `getUpdates` by default and also support webhooks for push delivery.
- Telethon and Pyrogram rely on the MTProto protocol over persistent TCP connections instead of the Bot API.
- `getUpdates` supports long polling via the `timeout` parameter (up to ~50 seconds) to minimize request churn.
- Telegram keeps one update queue per bot token; consuming an update by advancing the offset removes it from the queue.
- Updates expire after roughly a day if the bot stays offline without acknowledging them.
