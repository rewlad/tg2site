//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.fasterxml.jackson.databind.node.ArrayNode;

//import tools.jackson.core.

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// error util

interface RUCallable<T> {
    T call() throws Exception;
}

<T> T unchecked(RUCallable<T> body) {
    try {
        return body.call();
    } catch (Exception e) {
        throw (e instanceof RuntimeException re ? re : new RuntimeException(e));
    }
}

<R extends AutoCloseable, T> T using(Supplier<R> resource, Function<R, T> body) {
    try (R r = resource.get()) {
        return body.apply(r);
    } catch(Exception e){
        throw (e instanceof RuntimeException re ? re : new RuntimeException(e));
    }
}

<T> T expect(Function<String,T> get, String key, String hint){
    try {
        return get.apply(key);
    } catch (Exception e) {
        throw new RuntimeException("Bad or missing " + key + " in " + hint, e);
    }
}

// util

<T> void iterate(T state, Function<T,T> fn) {
    //noinspection InfiniteLoopStatement
    while(true) state = fn.apply(state);
}

void run(Path cwd, String... cmd) {
    if(unchecked(() -> new ProcessBuilder(cmd).directory(cwd.toFile()).inheritIO().start().waitFor()) != 0)
        throw new RuntimeException("Command failed: " + Arrays.toString(cmd));
}

// ----- app logic -----

void gitCloneRepo(Path root, String repo, String branch, String user) {
    run(Paths.get("."),"git","clone","--depth","1","--branch",branch,repo,root.toString());
    run(root, "git","config","user.email",user);
    run(root, "git","config","user.name", user);
}

void gitPull(Path root) {
    run(root, "git","pull");
}

void gitPush(Path root) {
    run(root, "git","add",".");
    run(root, "git","commit","-m","Sync updates");
    run(root, "git","push");
}

ArrayNode getUpdates(ObjectMapper mapper, String token, long offset) {
    final var uri = URI.create("https://api.telegram.org/bot" + token + "/getUpdates" + "?timeout=25&offset=" + offset);
    try (final var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
        final var req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
        final var resp = unchecked(() -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        if (resp.statusCode() != 200) throw new RuntimeException("getUpdates failed with HTTP " + resp.statusCode());
        final var payload = mapper.readTree(resp.body());
        if (!payload.get("ok").asBoolean()) throw new RuntimeException("Telegram getUpdates returned failure");
        return (ArrayNode) payload.get("result");
    }
}

record Msg(long chatId, String fileName, String body){}

void main(String[] args) {
    final var mapper = new ObjectMapper();
    final var conf = expect(k -> mapper.readTree(System.getenv(k)),"TG2SITE_CONF_CONTENT","env");
    final var secrets = expect(
            k -> mapper.readTree(unchecked(() -> Files.readString(Path.of(System.getenv(k))))),
            "TG2SITE_SECRETS_PATH","env"
    );
    final var repoURL   = expect(k -> secrets.get(k).asString(), "repository_url", "secret");
    final var branch    = expect(k -> conf.get(k).asString(),   "publish_branch", "conf");
    final var channelID = expect(k -> conf.get(k).asLong(),   "channel_id", "conf");
    final var token     = expect(k -> secrets.get(k).asString(),"telegram_token","secret");

    final var gitRoot = unchecked(() -> Files.createTempDirectory("tg2site-")).resolve("repo");
    System.err.println("cloning "+repoURL+" branch "+branch);
    gitCloneRepo(gitRoot, repoURL, branch, "bot@tg2site");
    final var msgDir = unchecked(() -> Files.createDirectories(gitRoot.resolve(".tg2site-messages")));
    final var msgKeys = List.of("channel_post", "edited_channel_post", "message", "edited_message");
    final var numR = Pattern.compile(".*/(\\d+).*\\.json$");
    final var msgFiles = using(() -> unchecked(()->Files.list(msgDir)), Stream::toList);
    final var maxId = msgFiles.stream().flatMap(path -> numR.matcher(path.toString()).results())
            .mapToLong(m -> Long.parseLong(m.group(1))).max().orElse(-1L);
    iterate(maxId, lastOffset -> {
        final var updates = getUpdates(mapper, token, lastOffset + 1);
        final var msgByChat = updates.valueStream().flatMap(upd -> msgKeys.stream().flatMap(mKey -> {
            final var message = upd.path(mKey);
            final var chatIdNode = message.path("chat").path("id");
            if (chatIdNode.isMissingNode()) return Stream.empty();
            final var path = "%020d.%s.json".formatted(upd.path("update_id").asLong(), mKey);
            final var body = mapper.writeValueAsString(message);
            return Stream.of(new Msg(chatIdNode.asLong(), path, body));
        })).collect(Collectors.groupingBy(Msg::chatId, Collectors.toList()));
        System.err.println("from chats: " + msgByChat.keySet().stream().sorted().toList());
        Stream.ofNullable(msgByChat.get(channelID)).forEach(relevant -> {
            gitPull(gitRoot);
            for (final var e: relevant)
                unchecked(() -> Files.writeString(gitRoot.resolve(".tg2site-messages").resolve(e.fileName), e.body));
            gitPush(gitRoot);
        });
        return updates.isEmpty() ? lastOffset : updates.get(updates.size()-1).get("update_id").asLong();
    });
}
