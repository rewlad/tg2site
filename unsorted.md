

lets start with plain structure: single bot.py in root; Dockerfile, github actions for building and registry; install2k8s.py later; all config from evniron:
  TG2SITE_CONF_CONTENT, TG2SITE_SECRETS_PATH - json format; terse style: heavy on comprehensions, construstion, minimal mutations;



mention too: methods on classes are rarely needed; choose comprehensions over loops with mutating operations (append, extend, `=`); choose simple construction (not comprehension) if lists are constant;








https://<TOKEN>@github.com/<owner>/<repo>.git



https://github.com/settings/personal-access-tokens
choose repo
permissions → Contents → Read and write


## Scala variants

- `bot.sc`: Ammonite-based script (Scala 2 runtime, minimal dependencies)
- `bot.scala-cli.sc`: Scala 3 Scala-CLI script (inline `using` deps, runnable via `scala-cli shebang`)

Dockerfiles include intentional comments that explain staging/caching choices—keep them unless directions change.
