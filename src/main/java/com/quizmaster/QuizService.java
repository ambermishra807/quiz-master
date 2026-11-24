package com.quizmaster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class QuizService {

    public static class QuizException extends RuntimeException {
        public QuizException(String m) { super(m); }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    // Token saver: identical requests reuse the stored question set (the browser still reshuffles it)
    private final Map<String, JsonNode> cache = new ConcurrentHashMap<>();
    private static final int CACHE_MAX = 200;

    @Value("${anthropic.api-key}") private String apiKey;
    @Value("${anthropic.model}") private String model;

    public JsonNode generate(String topic, String diff, int n, int sections) throws Exception {
        if (apiKey == null || apiKey.isBlank())
            throw new QuizException("Server is missing ANTHROPIC_API_KEY.");
        String key = (topic.toLowerCase() + "|" + diff + "|" + n + "|" + sections);
        JsonNode hit = cache.get(key);
        if (hit != null) return hit;

        int per = n / sections, rem = n - per * sections;
        StringBuilder counts = new StringBuilder();
        for (int i = 0; i < sections; i++) counts.append(i > 0 ? ", " : "").append(per + (i < rem ? 1 : 0));

        String prompt = "Write a " + diff + "-level multiple-choice quiz on the topic between triple quotes: \"\"\""
            + topic + "\"\"\". Split into " + sections + " distinct sub-topic sections with question counts " + counts + ". "
            + "Rules: accurate, unambiguous, non-overlapping questions; exactly one correct option and three plausible "
            + "distractors; no \"all/none of the above\". Each explanation max 2 sentences. Each wrong-option note max 1 short "
            + "sentence saying why that option is wrong. Source: one authoritative, stable page (official docs, standards body, "
            + "university, encyclopedia, government or textbook publisher) as [title,https url]; use only URLs you are highly "
            + "confident exist. Return ONLY compact JSON: {\"sections\":[{\"n\":\"section name\",\"qs\":[{\"q\":\"question\","
            + "\"a\":[\"CORRECT\",\"wrong1\",\"wrong2\",\"wrong3\"],\"e\":\"why correct\",\"w\":[\"why wrong1 is wrong\","
            + "\"why wrong2 is wrong\",\"why wrong3 is wrong\"],\"s\":[\"title\",\"https://...\"]}]}]}. The correct option MUST "
            + "be first in a; the app shuffles. If the topic is harmful or not a real subject, return {\"error\":\"reason\"}.";

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 600 + n * 330); // sized to the request, not a fixed large cap
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
            .timeout(Duration.ofSeconds(120))
            .header("content-type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("API status " + res.statusCode());

        String text = MAPPER.readTree(res.body()).path("content").path(0).path("text").asText("")
            .replaceAll("(?s)^\\s*```(?:json)?\\s*|\\s*```\\s*$", "").trim();
        JsonNode parsed = MAPPER.readTree(text);
        if (parsed.has("error")) throw new QuizException(parsed.get("error").asText());

        JsonNode clean = validate(parsed);
        if (cache.size() >= CACHE_MAX) cache.clear();
        cache.put(key, clean);
        return clean;
    }

    /** Keeps only well-formed questions (4 distinct options, 3 wrong-notes, https source). */
    private JsonNode validate(JsonNode root) {
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode secs = out.putArray("sections");
        for (JsonNode s : root.path("sections")) {
            if (secs.size() == 3) break;
            ArrayNode qs = MAPPER.createArrayNode();
            for (JsonNode q : s.path("qs")) {
                JsonNode a = q.path("a"), w = q.path("w");
                if (!q.path("q").isTextual() || !a.isArray() || a.size() != 4 || !w.isArray() || w.size() != 3) continue;
                Set<String> uniq = new HashSet<>();
                a.forEach(x -> uniq.add(x.asText()));
                if (uniq.size() != 4) continue;
                JsonNode src = q.path("s");
                if (!(src.isArray() && src.size() == 2 && src.get(1).asText().startsWith("https://")))
                    ((ObjectNode) q).remove("s");
                qs.add(q);
            }
            if (qs.size() > 0) {
                ObjectNode sec = secs.addObject();
                sec.put("n", s.path("n").asText("Section " + (secs.size())));
                sec.set("qs", qs);
            }
        }
        if (secs.size() < 2) throw new QuizException("Not enough valid questions were returned. Try again.");
        return out;
    }
}
