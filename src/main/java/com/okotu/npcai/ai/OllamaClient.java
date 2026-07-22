package com.okotu.npcai.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.okotu.npcai.config.PluginConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client for Ollama's /api/chat endpoint. All calls are async
 * (java.net.http.HttpClient + CompletableFuture): they never block the main thread.
 */
public class OllamaClient {

    private final PluginConfig config;
    private final Logger logger;
    private final HttpClient httpClient;

    public OllamaClient(PluginConfig config, Logger logger, Executor executor) {
        this.config = config;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.ollamaTimeoutMs))
                .executor(executor)
                .build();
    }

    /**
     * One chat turn: [system prompt] + [history] + [current user message].
     *
     * @param model        Ollama model tag to use (e.g. "qwen2.5:1.5b")
     * @param systemPrompt already-built system prompt (character sheet + memory + knowledge + context)
     * @param messages     history already formatted as role/content pairs (without the system prompt)
     * @param userMessage  the player's latest message
     * @return the NPC's reply text
     */
    public CompletableFuture<String> chat(String model, String systemPrompt,
                                           List<ChatMessage> messages, String userMessage) {
        JsonObject body = buildRequestBody(model, systemPrompt, messages, userMessage);
        return attemptWithRetries(body, config.ollamaMaxRetries);
    }

    private CompletableFuture<String> attemptWithRetries(JsonObject body, int retriesLeft) {
        return sendRequest(body).handle((result, throwable) -> {
            if (throwable == null) {
                return CompletableFuture.completedFuture(result);
            }
            if (retriesLeft <= 0) {
                CompletableFuture<String> failed = new CompletableFuture<>();
                failed.completeExceptionally(throwable);
                return failed;
            }
            logger.log(Level.WARNING, "Request to Ollama failed, retrying ("
                    + retriesLeft + " attempts left): " + throwable.getMessage());
            CompletableFuture<String> delayed = new CompletableFuture<>();
            CompletableFuture.delayedExecutor(config.ollamaRetryDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .execute(() -> attemptWithRetries(body, retriesLeft - 1)
                            .whenComplete((r, t) -> {
                                if (t != null) delayed.completeExceptionally(t);
                                else delayed.complete(r);
                            }));
            return delayed;
        }).thenCompose(future -> future);
    }

    private CompletableFuture<String> sendRequest(JsonObject body) {
        String url = config.ollamaBaseUrl + "/api/chat";

        if (config.debugLogOllamaCommunication) {
            logger.info("[Ollama DEBUG] Request -> " + url + "\n" + body);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(config.ollamaTimeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (config.debugLogOllamaCommunication) {
                        logger.info("[Ollama DEBUG] Response (HTTP " + response.statusCode() + ") <- "
                                + url + "\n" + response.body());
                    }
                    if (response.statusCode() / 100 != 2) {
                        throw new CompletionException(
                                new RuntimeException("Ollama responded with HTTP " + response.statusCode()
                                        + ": " + truncate(response.body(), 300)));
                    }
                    return extractContent(response.body());
                });
    }

    private JsonObject buildRequestBody(String model, String systemPrompt,
                                         List<ChatMessage> history, String userMessage) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);

        JsonArray messages = new JsonArray();
        messages.add(chatMessageJson("system", systemPrompt));
        for (ChatMessage m : history) {
            messages.add(chatMessageJson(m.role(), m.content()));
        }
        messages.add(chatMessageJson("user", userMessage));
        body.add("messages", messages);

        return body;
    }

    private JsonObject chatMessageJson(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private String extractContent(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonObject message = root.getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            throw new CompletionException(new RuntimeException("Ollama response is missing message.content"));
        }
        return message.get("content").getAsString().trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Role/content pair for the history passed to /api/chat. */
    public record ChatMessage(String role, String content) {
        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }
    }
}
