from json import dumps, loads
from logging import basicConfig, info
from os import environ
from pathlib import Path
from subprocess import check_call
from tempfile import mkdtemp
from typing import Dict, List, NoReturn
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from collections import defaultdict
from re import search

def die(exc: Exception) -> NoReturn: raise exc

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])

def grouped(pairs): # mutation allowed as low level optimization
    res = defaultdict(list)
    for k, v in pairs: res[k].append(v)
    return res

def expect(get, key, hint):
    try: return get(key)
    except Exception: die(ValueError(f"Bad or missing {key} in {hint}"))

def get_tg_updates(telegram_token: str, offset: int) -> dict:
    query = urlencode({"timeout": 25, "offset": offset})
    request = Request(f"https://api.telegram.org/bot{telegram_token}/getUpdates?{query}")
    with urlopen(request, timeout=30) as resp:
        resp.status == 200 or die(RuntimeError(f"getUpdates failed with HTTP {resp.status}"))
        payload = loads(resp.read())
        payload["ok"] or die(RuntimeError("Telegram getUpdates returned failure"))
        return payload["result"]

def git_clone(repo_url, branch, worktree, user):
    check_call(("git", "clone", "--depth", "1", "--branch", branch, repo_url, worktree)) # clone to empty dir works
    check_call(("git", "config", "user.email", user), cwd=worktree)
    check_call(("git", "config", "user.name", user), cwd=worktree)
    def add(sub, files):
        check_call(("git", "pull"), cwd=worktree)
        for name, content in files: (Path(worktree) / sub / name).write_bytes(content.encode())
        check_call(("git", "add", "."), cwd=worktree)
        check_call(("git", "commit", "-m", f"Sync updates"), cwd=worktree)
        check_call(("git", "push"), cwd=worktree)
    return add

def main(args: list[str] | None = None) -> None:
    basicConfig(level=environ.get("TG2SITE_LOG_LEVEL", "INFO").upper())
    conf = expect(lambda k: loads(environ[k]), "TG2SITE_CONF_CONTENT", "env")
    secrets = expect(lambda k: loads(Path(environ[k]).read_bytes()), "TG2SITE_SECRETS_PATH", "env")
    repo_url = expect(lambda k: secrets[k], "repository_url", "secret")
    publish_branch = expect(lambda k: conf[k], "publish_branch", "conf")
    channel_id = expect(lambda k: int(conf[k]), "channel_id", "conf")
    telegram_token = expect(lambda k: secrets[k], "telegram_token", "secret")

    worktree = Path(mkdtemp(prefix="tg2site-")) / "repo"
    info("cloning repo=%s branch=%s into %s", repo_url, publish_branch, worktree)
    add = git_clone(repo_url, publish_branch, str(worktree), "bot@tg2site")
    messages_dir = worktree / ".tg2site-messages"
    messages_dir.mkdir(exist_ok=True)
    message_keys = ("channel_post", "edited_channel_post", "message", "edited_message")
    last_offset = max([
        int(m[1])
        for path in messages_dir.iterdir() for m in [search(r"^(\d+).*\.json$", path.name)] if m
    ], default=-1)
    while True:
        updates = get_tg_updates(telegram_token, last_offset + 1)
        updates_by_channel = grouped(
            (sel(msg, "chat", "id"), (path, dumps(msg, sort_keys=True)))
            for u in updates for message_key in message_keys for msg in [u.get(message_key)] if msg
            for path in [f"{int(u["update_id"]):020}.{message_key}.json"]
        )
        info(f'got updates from: {[*updates_by_channel.keys()]}')
        relevant = updates_by_channel.get(channel_id)
        if relevant: add(".tg2site-messages", relevant)
        if updates: last_offset = updates[-1]["update_id"]

main()
