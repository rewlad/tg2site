from json import dumps, loads
from logging import basicConfig, info, warning
from os import environ
from pathlib import Path
from subprocess import check_call
from tempfile import mkdtemp
from typing import Dict, List, NoReturn
from urllib.parse import urlencode
from urllib.request import Request, urlopen

def never(exc: Exception) -> NoReturn:
    raise exc

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])

def grouped(pairs): # mutation allowed as low level optimization
    res = {}
    for k, v in pairs:
        vs = res.get(k)
        if vs is None: res[k] = [v]
        else: vs.append(v)
    return res

def get_tg_updates(telegram_token: str, offset: int) -> dict:
    query = urlencode({"timeout": 25, "offset": offset})
    request = Request(f"https://api.telegram.org/bot{telegram_token}/getUpdates?{query}")
    with urlopen(request, timeout=30) as resp:
        resp.status == 200 or never(RuntimeError(f"getUpdates failed with HTTP {resp.status}"))
        payload = loads(resp.read())
        payload["ok"] or never(RuntimeError("Telegram getUpdates returned failure"))
        return payload["result"]

def git_clone(repo_url, branch, worktree, user):
    check_call(("git", "clone", "--depth", "1", "--branch", branch, repo_url, worktree)) # clone to empty dir works
    check_call(("git", "config", "user.email", user), cwd=worktree)
    check_call(("git", "config", "user.name", user), cwd=worktree)
    def pull(): check_call(("git", "pull"), cwd=worktree)
    def push():
        check_call(("git", "add", "."), cwd=worktree)
        check_call(("git", "commit", "-m", f"Sync updates"), cwd=worktree)
        check_call(("git", "push"), cwd=worktree)
    return pull, push

def main(args: list[str] | None = None) -> None:
    basicConfig(level=environ.get("TG2SITE_LOG_LEVEL", "INFO").upper())

    conf_content = environ.get("TG2SITE_CONF_CONTENT") or never(ValueError("Missing TG2SITE_CONF_CONTENT"))
    secrets_path = environ.get("TG2SITE_SECRETS_PATH") or never(ValueError("Missing TG2SITE_SECRETS_PATH"))

    conf = loads(conf_content)
    secrets = loads(Path(secrets_path).read_bytes())

    repo_url = secrets.get("repository_url") or never(ValueError("Missing repository_url secret"))
    publish_branch = conf.get("publish_branch") or never(ValueError("Missing publish_branch"))
    channel_id = int(conf.get("channel_id") or never(ValueError("Missing channel_id")))
    telegram_token = secrets.get("telegram_token") or never(ValueError("Missing telegram_token secret"))

    worktree = Path(mkdtemp(prefix="tg2site-")) / "repo"
    info("cloning repo=%s branch=%s into %s", repo_url, publish_branch, worktree)

    pull, push = git_clone(repo_url, publish_branch, str(worktree), "bot@tg2site")

    messages_dir = worktree / ".tg2site-messages"
    messages_dir.mkdir(exist_ok=True)

    last_offset = max([int(path.stem) for path in messages_dir.iterdir() if path.suffix == ".json"], default=-1)
    while True:
        updates = get_tg_updates(telegram_token, last_offset + 1)
        if not updates: continue
        last_offset = updates[-1]["update_id"]
        updates_by_channel = grouped((sel(u, "message", "chat", "id"), u) for u in updates)
        info(f'got updates from: {[*updates_by_channel.keys()]}')
        my_updates = updates_by_channel.get(channel_id)
        if not my_updates: continue
        pull()
        for u in my_updates:
            (messages_dir / f'{u["update_id"]}.json').write_bytes(dumps(u["message"], sort_keys=True).encode())
        push()

if __name__ == "__main__":
    main()
