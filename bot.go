package main

import (
	"encoding/json"
	"fmt"
	"io"
	"iter"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
)

// ----- general helpers -----

// collection util

type Yield[V any] = func(V) bool
type Yield2[K, V any] = func(K,V) bool

func GroupBy[K comparable, V any](it iter.Seq2[K,V]) map[K][]V {
	out := make(map[K][]V)
	for k, v := range it {
		out[k] = append(out[k], v)
	}
	return out
}

// error util

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func must2[T any](v T, err error) T {
	must(err)
	return v
}

func expect[T any](get func(string) T, key, hint string) T {
	defer func() {
		if r := recover(); r != nil {
			panic(fmt.Errorf("bad or missing %s in %s: %v", key, hint, r))
		}
	}()
	return get(key)
}

// json util

func sel(v any, path ...string) any {
	if len(path) == 0 || v == nil {
		return v
	}
	return sel(v.(map[string]any)[path[0]], path[1:]...)
}

func parseJSON(b []byte) map[string]any {
	var v map[string]any
	must(json.Unmarshal(b, &v))
	return v
}

func toInt64(v any) int64 {
	return int64(v.(float64))
}

// util

func foreverFrom[T any](init T, fn func(T) T) {
	for cur := init; ; cur = fn(cur) {
	}
}

func run(cwd, name string, args ...string) {
	cmd := exec.Command(name, args...)
	cmd.Dir = cwd
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	must(cmd.Run())
}

// ----- app logic -----

func gitClone(worktree, repo, branch, user string) {
	run("", "git", "clone", "--depth", "1", "--branch", branch, repo, worktree)
	run(worktree, "git", "config", "user.email", user)
	run(worktree, "git", "config", "user.name", user)
}

func gitAdd(worktree, sub string, files [][2]string) {
	run(worktree, "git", "pull")
	for _, p := range files {
		path := filepath.Join(worktree, sub, p[0])
		must(os.WriteFile(path, []byte(p[1]), 0o644))
	}
	run(worktree, "git", "add", ".")
	run(worktree, "git", "commit", "-m", "Sync updates")
	run(worktree, "git", "push")
}

func getUpdates(token string, offset int64) []any {
	url := fmt.Sprintf("https://api.telegram.org/bot%s/getUpdates?timeout=25&offset=%d", token, offset)
	resp, err := (&http.Client{Timeout: 30_000_000_000}).Get(url) // 30s
	defer resp.Body.Close()
	must(err)
	if resp.StatusCode != 200 {
		panic(fmt.Errorf("getUpdates HTTP %d", resp.StatusCode))
	}
	v := parseJSON(must2(io.ReadAll(resp.Body)))
	if !sel(v, "ok").(bool) {
		panic("Telegram getUpdates returned failure")
	}
	return sel(v, "result").([]any)
}

func main() {
	conf := expect(func(k string) map[string]any { return parseJSON([]byte(os.Getenv(k))) }, "TG2SITE_CONF_CONTENT", "env")
	secrets := expect(func(k string) map[string]any { return parseJSON(must2(os.ReadFile(os.Getenv(k)))) }, "TG2SITE_SECRETS_PATH", "env")
	repoURL := expect(func(k string) string { return sel(secrets, k).(string) }, "repository_url", "secret")
	publishBranch := expect(func(k string) string { return sel(conf, k).(string) }, "publish_branch", "conf")
	channelID := expect(func(k string) int64 { return toInt64(sel(conf, k)) }, "channel_id", "conf")
	tgToken := expect(func(k string) string { return sel(secrets, k).(string) }, "telegram_token", "secret")

	tmp := must2(os.MkdirTemp("", "tg2site-"))
	defer os.RemoveAll(tmp)
	worktree := filepath.Join(tmp, "repo")
	fmt.Fprintf(os.Stderr, "cloning repo=%s branch=%s into %s\n", repoURL, publishBranch, worktree)
	gitClone(worktree, repoURL, publishBranch, "bot@tg2site")
	msgDir := filepath.Join(worktree, ".tg2site-messages")
	must(os.MkdirAll(msgDir, 0o755))
	msgKeys := []string{"channel_post", "edited_channel_post", "message", "edited_message"}
	re := regexp.MustCompile(`^(\d+).*\.json$`)
	maxID := slices.Max(slices.Collect(func(yield Yield[int64]) {
		yield(-1)
		for _, f := range must2(os.ReadDir(msgDir)) {
			m := re.FindStringSubmatch(f.Name())
			if m != nil {
				yield(must2(strconv.ParseInt(m[1], 10, 64)))
			}
		}
	}))
	foreverFrom[int64](maxID, func(last int64) int64 {
		updates := getUpdates(tgToken, last+1)
		msgs := GroupBy(func(yield Yield2[int64, [2]string]) {
			for _, upd := range updates {
				updateId := toInt64(sel(upd, "update_id"))
				for _, mKey := range msgKeys {
					m := sel(upd, mKey)
					chatId := sel(m, "chat", "id")
					if chatId == nil {
						continue
					}
					path := fmt.Sprintf("%020d.%s.json", updateId, mKey)
					b := must2(json.Marshal(m))
					yield(toInt64(chatId), [2]string{path, string(b)})
				}
			}
		})
		chatIdsStr := strings.Join(slices.Collect(func(yield Yield[string]){
			for cid := range msgs {
				yield(strconv.FormatInt(cid, 10))
			}
		}), " ")
		fmt.Fprintf(os.Stderr, "got updates from chats: %s\n", chatIdsStr)
		if rel, ok := msgs[channelID]; ok {
			gitAdd(worktree, ".tg2site-messages", rel)
		}
		if len(updates) > 0 {
			return toInt64(sel(updates[len(updates)-1], "update_id"))
		}
		return last
	})
}
