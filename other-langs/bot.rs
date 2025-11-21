use std::env;
use std::fs;
use std::path::Path;
use std::process::Command;
use std::time::Duration;

use anyhow::{anyhow, Context, Result};
use reqwest::Client;
use serde::Deserialize;
use serde_json::{value::RawValue, Value};
use tempfile::Builder;

#[derive(Deserialize)]
struct UpdatesResponse {
    ok: bool,
    result: Vec<Update>,
}

#[derive(Deserialize)]
struct Update<'a> {
    update_id: i64,
    #[serde(default)]
    message: Option<&'a RawValue>,
    #[serde(default)]
    edited_message: Option<&'a RawValue>,
    #[serde(default)]
    channel_post: Option<&'a RawValue>,
    #[serde(default)]
    edited_channel_post: Option<&'a RawValue>,
}

fn required_env(key: &str) -> Result<String> {
    env::var(key).with_context(|| format!("Missing {}", key))
}

fn run(command: &str, args: &[&str], cwd: Option<&Path>) -> Result<()> {
    let mut cmd = Command::new(command);
    cmd.args(args);
    if let Some(dir) = cwd {
        cmd.current_dir(dir);
    }
    let status = cmd.status().with_context(|| format!("Failed to spawn {}", command))?;
    if !status.success() {
        return Err(anyhow!(
            "{} exited with status {}",
            command,
            status.code().unwrap_or(-1)
        ));
    }
    Ok(())
}

fn git_clone(repo_url: &str, branch: &str, worktree: &Path, user: &str) -> Result<(impl Fn() -> Result<()>, impl Fn() -> Result<()>)> {
    run(
        "git",
        &["clone", "--depth", "1", "--branch", branch, repo_url, worktree.to_str().unwrap()],
        None,
    )?;
    run("git", &["config", "user.email", user], Some(worktree))?;
    run("git", &["config", "user.name", user], Some(worktree))?;
    let pull_worktree = worktree.to_owned();
    let push_worktree = worktree.to_owned();
    let pull = move || run("git", &["pull"], Some(&pull_worktree));
    let push = move || {
        run("git", &["add", "."], Some(&push_worktree))?;
        run("git", &["commit", "-m", "Sync updates"], Some(&push_worktree))?;
        run("git", &["push"], Some(&push_worktree))
    };
    Ok((pull, push))
}

async fn get_updates<'a>(client: &Client, token: &str, offset: i64) -> Result<Vec<Update<'a>>> {
    let url = format!(
        "https://api.telegram.org/bot{}/getUpdates?timeout=25&offset={}",
        token, offset
    );
    let resp = client.get(url).send().await?;
    if !resp.status().is_success() {
        return Err(anyhow!(
            "getUpdates failed with HTTP {}",
            resp.status().as_u16()
        ));
    }
    let payload: UpdatesResponse = resp.json().await?;
    if !payload.ok {
        return Err(anyhow!("Telegram getUpdates returned failure"));
    }
    Ok(payload.result)
}

fn primary_message(update: &Update<'_>) -> Option<&RawValue> {
    update.channel_post.or(update.edited_channel_post).or(update.message).or(update.edited_message)
}

fn parse_channel_id(value: &Value) -> Option<i64> {
    match value {
        Value::Number(num) => num.as_i64(),
        Value::String(text) => text.parse::<i64>().ok(),
        _ => None,
    }
}

fn message_chat_id(value: &RawValue) -> Option<i64> {
    let parsed: Value = serde_json::from_str(value.get()).ok()?;
    parsed
        .get("chat")
        .and_then(|chat| chat.get("id"))
        .and_then(parse_channel_id)
}

fn last_offset_from_dir(path: &Path) -> Result<i64> {
    let entries = match fs::read_dir(path) {
        Ok(entries) => entries,
        Err(_) => return Ok(-1),
    };
    let mut max_offset = -1;
    for entry in entries {
        let entry = entry?;
        if entry.file_type()?.is_dir() {
            continue;
        }
        if let Some(name) = entry.file_name().to_str() {
            if let Some(stripped) = name.strip_suffix(".json") {
                if let Ok(value) = stripped.parse::<i64>() {
                    if value > max_offset {
                        max_offset = value;
                    }
                }
            }
        }
    }
    Ok(max_offset)
}

fn parse_channel_id_conf(value: &Value) -> Result<i64> {
    parse_channel_id(value).ok_or_else(|| anyhow!("Invalid channel_id"))
}

#[tokio::main]
async fn main() -> Result<()> {
    let log_level = env::var("TG2SITE_LOG_LEVEL")
        .unwrap_or_else(|_| "INFO".to_string())
        .to_uppercase();

    let conf_content = required_env("TG2SITE_CONF_CONTENT")?;
    let secrets_path = required_env("TG2SITE_SECRETS_PATH")?;

    let conf: Value = serde_json::from_str(&conf_content).context("Invalid conf JSON")?;
    let secrets_content = fs::read_to_string(&secrets_path)
        .with_context(|| format!("Failed to read {}", secrets_path))?;
    let secrets: Value = serde_json::from_str(&secrets_content).context("Invalid secrets JSON")?;

    let repo_url = secrets
        .get("repository_url")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| anyhow!("Missing repository_url secret"))?;

    let publish_branch = conf
        .get("publish_branch")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| anyhow!("Missing publish_branch"))?;

    let channel_id = conf
        .get("channel_id")
        .map(parse_channel_id_conf)
        .unwrap_or_else(|| Err(anyhow!("Missing channel_id")))?;

    let telegram_token = secrets
        .get("telegram_token")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| anyhow!("Missing telegram_token secret"))?;

    let temp_dir = Builder::new().prefix("tg2site-").tempdir()?;
    let worktree = temp_dir.path().join("repo");
    println!(
        "[{}] cloning repo={} branch={} into {}",
        log_level,
        repo_url,
        publish_branch,
        worktree.display()
    );

    let (pull, push) = git_clone(repo_url, publish_branch, &worktree, "bot@tg2site")?;

    let messages_dir = worktree.join(".tg2site-messages");
    fs::create_dir_all(&messages_dir).context("Failed to create messages directory")?;

    let mut last_offset = last_offset_from_dir(&messages_dir)?;
    let client = Client::builder()
        .timeout(Duration::from_secs(30))
        .build()?;

    loop {
        let updates = match get_updates(&client, telegram_token, last_offset + 1).await {
            Ok(updates) => updates,
            Err(err) => {
                println!("[{}] warning: failed to fetch updates: {}", log_level, err);
                tokio::time::sleep(Duration::from_secs(2)).await;
                continue;
            }
        };
        if updates.is_empty() {
            continue;
        }
        last_offset = updates.last().map(|u| u.update_id).unwrap_or(last_offset);

        let mut relevant: Vec<&Update> = Vec::new();
        for update in &updates {
            if let Some(message) = primary_message(update) {
                if message_chat_id(message) == Some(channel_id) {
                    relevant.push(update);
                }
            }
        }
        if relevant.is_empty() {
            continue;
        }

        pull()?;
        for update in relevant {
            if let Some(message) = primary_message(update) {
                let target = messages_dir.join(format!("{}.json", update.update_id));
                fs::write(&target, message.get())?;
            }
        }
        push()?;
    }
}
