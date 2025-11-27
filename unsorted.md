

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

## Мысли

Я тут ради интереса реализую одно и то же на разных технологиях.
Созерцаю выразительность языков.
Пока получается так - логика приложения (без учета совсем общих хелперов), строк:
- scala, python - 50
- java - 70
- go - 90

В go получается многословней - нету тернарного оператора (1) и вывода типов у лямбд (2).

(1)
`var a = c ? b() : d();`
vs
```
var a AComplexType
if c {
    a = b()
} else {
    a = d()
}
```
Реально оно вынесется в функцию, чтобы не засорять обзор: `a := getA(...`

(2)
`var a = f(k -> g(j,k));`
vs
`a := f(func(k AComplexType) AComplexType { return g(j,k) })`

Это вещи, которые нельзя вынести в библиотеку (как тот же http server) и вызвать парой строк.
