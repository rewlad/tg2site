import scala.annotation.tailrec
import scala.util.Try
import sys.env

def expect[T](get: String => T, key: String, hint: String): T =
  Try(get(key)).getOrElse(sys.error(s"Bad or missing $key in $hint"))

class Git(val worktree: os.Path):
  def clone(repo: String, branch: String, user: String): Unit =
    os.proc("git", "clone", "--depth", "1", "--branch", branch, repo, worktree.toString)
      .call(stdout = os.Inherit)
    os.proc("git", "config", "user.email", user).call(cwd = worktree, stdout = os.Inherit)
    os.proc("git", "config", "user.name", user).call(cwd = worktree, stdout = os.Inherit)
  def add(sub: String, files: Seq[(String, String)]): Unit =
    os.proc("git", "pull").call(cwd = worktree, stdout = os.Inherit)
    for (name, content) <- files do os.write.over(worktree / sub / name, content)
    os.proc("git", "add", ".").call(cwd = worktree, stdout = os.Inherit)
    os.proc("git", "commit", "-m", "Sync updates").call(cwd = worktree, stdout = os.Inherit)
    os.proc("git", "push").call(cwd = worktree, stdout = os.Inherit)

def getUpdates(token: String, offset: Long): Seq[ujson.Value] =
  val url = s"https://api.telegram.org/bot$token/getUpdates"
  val params = Map("timeout" -> "25", "offset" -> offset.toString)
  val resp = requests.get(url, params = params, readTimeout = 30_000, connectTimeout = 30_000)
  if resp.statusCode != 200 then sys.error(s"getUpdates failed with HTTP ${resp.statusCode}")
  val payload = ujson.read(resp.text())
  if !payload("ok").bool then sys.error("Telegram getUpdates returned failure")
  payload("result").arr.toSeq

@main def theMain(): Unit =
  val confObj = expect(k => ujson.read(env(k)), "TG2SITE_CONF_CONTENT", "env")
  val secretsObj = expect(k => ujson.read(os.read(os.Path(env(k)))), "TG2SITE_SECRETS_PATH", "env")
  val repoURL = expect(secretsObj(_).str, "repository_url", "secret")
  val publishBranch = expect(confObj(_).str, "publish_branch", "conf")
  val channelID = expect(confObj(_).num.toLong, "channel_id", "conf")
  val telegramToken = expect(secretsObj(_).str, "telegram_token", "secret")

  val git = new Git(os.temp.dir(prefix = "tg2site-") / "repo")
  System.err.println(s"cloning repo=$repoURL branch=$publishBranch into ${git.worktree}")
  git.clone(repoURL, publishBranch, "bot@tg2site")
  val messagesDir = git.worktree / ".tg2site-messages"
  os.makeDir.all(messagesDir)
  val messageKeys = Seq("channel_post", "edited_channel_post", "message", "edited_message")
  @tailrec def iteration(lastOffset: Long): Unit =
    val updates = getUpdates(telegramToken, lastOffset + 1)
    val messagesByChat = (for
      update <- updates
      messageKey <- messageKeys
      message <- update.obj.get(messageKey)
      chatId <- Try(message("chat")("id").num.toLong).toOption
      path = f"${update("update_id").num.toLong}%020d.$messageKey.json"
    yield chatId -> (path -> ujson.write(message))).groupMap(_._1)(_._2)
    System.err.println(s"got updates from: ${messagesByChat.keys.toSeq.sorted}")
    for relevant <- messagesByChat.get(channelID) do git.add(".tg2site-messages", relevant)
    iteration(if updates.isEmpty then lastOffset else updates.last("update_id").num.toLong)
  val NumP = """.*/(\d+).*\.json""".r
  val messages = os.list(messagesDir)
  iteration(messages.map(_.toString).collect{ case NumP(s) => s.toLong }.maxOption.getOrElse(-1))

/***
`System.err` usage is intentional
***/
