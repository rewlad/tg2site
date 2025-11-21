import { mkdir, mkdtemp, readFile, readdir, writeFile } from "fs/promises"
import { spawn } from "child_process"
import { once } from "node:events"
import { tmpdir } from "os"
import { join } from "path"

type Message = {
  chat?: {
    id?: number
  }
  [key: string]: unknown
}

type Update = {
  update_id: number
  message?: Message
  edited_message?: Message
  channel_post?: Message
  edited_channel_post?: Message
  [key: string]: unknown
}

type UpdatesResponse = {
  ok: boolean
  result: Update[]
}

const die = (error: Error | string): never => {
  throw typeof error === "string" ? new Error(error) : error
}

const primaryMessage = (update: Update): Message | undefined =>
  update.channel_post ?? update.edited_channel_post ?? update.message ?? update.edited_message

const run = async (command: string, args: string[], options?: { cwd?: string }): Promise<void> => {
  const child = spawn(command, args, { stdio: "inherit", cwd: options?.cwd })
  const [code, signal] = await Promise.race([
    once(child, "close") as Promise<[number | null, NodeJS.Signals | null]>,
    once(child, "error").then(([error]) => die(error as Error)),
  ])
  code === 0 || die(`${command} exited with code ${code ?? -1}`)
}

const expectInteger = (value: unknown, label: string): number =>
  typeof value === "number" && Number.isInteger(value) ? value : die(`Invalid ${label}`)

const expectNonEmptyString = (value: unknown, label: string): string =>
  typeof value === "string" && value.length > 0 ? value : die(`Missing ${label}`)

const getUpdates = async (token: string, offset: number): Promise<Update[]> => {
  const response = await fetch(
    `https://api.telegram.org/bot${token}/getUpdates?timeout=25&offset=${offset}`,
    { signal: AbortSignal.timeout(30_000) },
  )
  response.ok || die(`getUpdates failed with HTTP ${response.status}`)
  const payload = (await response.json()) as UpdatesResponse
  return payload.ok ? payload.result : die("Telegram getUpdates returned failure")
}

const gitClone = async (repoURL: string, branch: string, worktree: string, user: string) => {
  await run("git", ["clone", "--depth", "1", "--branch", branch, repoURL, worktree])
  await run("git", ["config", "user.email", user], { cwd: worktree })
  await run("git", ["config", "user.name", user], { cwd: worktree })
  const pull = () => run("git", ["pull"], { cwd: worktree })
  const push = async () => {
    await run("git", ["add", "."], { cwd: worktree })
    await run("git", ["commit", "-m", "Sync updates"], { cwd: worktree })
    await run("git", ["push"], { cwd: worktree })
  }
  return { pull, push }
}

const lastOffsetFromDir = async (path: string): Promise<number> => {
  const offsets = (await readdir(path, { withFileTypes: true }))
    .filter((entry) => entry.isFile() && entry.name.endsWith(".json"))
    .map((entry) => Number.parseInt(entry.name.slice(0, -5), 10))
    .filter((value) => Number.isInteger(value))
  return offsets.length === 0 ? -1 : Math.max(...offsets)
}

const main = async (): Promise<void> => {
  const confContent = expectNonEmptyString(process.env["TG2SITE_CONF_CONTENT"], "TG2SITE_CONF_CONTENT")
  const secretsPath = expectNonEmptyString(process.env["TG2SITE_SECRETS_PATH"], "TG2SITE_SECRETS_PATH")

  const conf = JSON.parse(confContent) as Record<string, unknown>
  const secrets = JSON.parse(await readFile(secretsPath, "utf8")) as Record<string, unknown>

  const repoURL = expectNonEmptyString(secrets["repository_url"], "repository_url secret")
  const publishBranch = expectNonEmptyString(conf["publish_branch"], "publish_branch")
  const channelID = expectInteger(conf["channel_id"], "channel_id")
  const telegramToken = expectNonEmptyString(secrets["telegram_token"], "telegram_token secret")

  const worktree = join(await mkdtemp(join(tmpdir(), "tg2site-")), "repo")
  console.log(`cloning repo=${repoURL} branch=${publishBranch} into ${worktree}`)
  const { pull, push } = await gitClone(repoURL, publishBranch, worktree, "bot@tg2site")

  const messagesDir = join(worktree, ".tg2site-messages")
  await mkdir(messagesDir, { recursive: true })

  const iteration = async (offset: number): Promise<never> => {
    const updates = await getUpdates(telegramToken, offset + 1)
    const nextOffset = updates.length === 0 ? offset : updates.at(-1)!.update_id
    const relevant = updates
      .map((update) => [update, primaryMessage(update)] as const)
      .filter(([, message]) => message?.chat?.id === channelID)
    if (relevant.length > 0) {
      await pull()
      for (const [update, message] of relevant) {
        const target = join(messagesDir, `${update.update_id}.json`)
        await writeFile(target, JSON.stringify(message!), "utf8")
      }
      await push()
    }
    return iteration(nextOffset)
  }

  await iteration(await lastOffsetFromDir(messagesDir))
}

await main()
