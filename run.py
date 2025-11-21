#!/usr/bin/env python3

from subprocess import run
from os import environ
from pathlib import Path
from sys import argv

variant = argv[1]
parent = Path(__file__).parent
run(("docker","build","-t",f"sk-tg2site:{variant}","-f", str(parent/f"bot.{variant}.dockerfile"), str(parent)), check=True)
run(("docker","stop","tg2site"), check=False)
run((
    "docker","run","--rm","-i","--name","tg2site","--network","host",
    "-e",f'TG2SITE_CONF_CONTENT={environ["TG2SITE_CONF_CONTENT"]}',
    "-e",f'TG2SITE_SECRETS_PATH=/bot.secret.json', "-v", f'{environ["TG2SITE_SECRETS_PATH"]}:/bot.secret.json',
    f"sk-tg2site:{variant}"
), check=True)
