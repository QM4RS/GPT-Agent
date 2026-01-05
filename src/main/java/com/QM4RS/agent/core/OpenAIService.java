package com.QM4RS.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class OpenAIService {

    public record OpenAIResult(
            String diffText,
            String rawText,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens
    ) {}

    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final ObjectMapper om = new ObjectMapper();

    private String apiKey;

    public void configure(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key is empty.");
        }
        this.apiKey = apiKey.trim();
    }

    public OpenAIResult generateDiff(String model, String input) throws IOException, InterruptedException {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("OpenAI client is not configured.");
        if (model == null || model.isBlank()) model = "gpt-4.1";

        String bodyJson = """
        {
          "model": %s,
          "input": %s
        }
        """.formatted(
                encouragesJsonString(model),
                encouragesJsonString(input)
        );

        HttpRequest req = HttpRequest.newBuilder()
                .uri(RESPONSES_URI)
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        String raw = resp.body() == null ? "" : resp.body();

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("OpenAI HTTP " + resp.statusCode() + ": " + raw);
        }

        JsonNode root = om.readTree(raw);

        String outText = extractOutputText(root);
        Integer inTok = intOrNull(root.at("/usage/input_tokens"));
        Integer outTok = intOrNull(root.at("/usage/output_tokens"));
        Integer totalTok = intOrNull(root.at("/usage/total_tokens"));

        return new OpenAIResult(outText, raw, inTok, outTok, totalTok);
    }

    private static String extractOutputText(JsonNode root) {
        if (root == null) return "";

        StringBuilder sb = new StringBuilder();
        JsonNode output = root.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.get("content");
                if (content != null && content.isArray()) {
                    for (JsonNode c : content) {
                        String type = textOrEmpty(c.get("type"));
                        if ("output_text".equals(type)) {
                            String t = textOrEmpty(c.get("text"));
                            if (!t.isBlank()) {
                                if (sb.length() > 0) sb.append("\n");
                                sb.append(t);
                            }
                        }
                    }
                }
            }
        }

        if (sb.isEmpty()) {
            String maybe = textOrEmpty(root.get("output_text"));
            if (!maybe.isBlank()) return maybe;
        }

        return sb.toString().trim();
    }

    private static Integer intOrNull(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isInt()) return n.intValue();
        if (n.isNumber()) return n.numberValue().intValue();
        try { return Integer.parseInt(n.asText()); } catch (Exception ignored) { return null; }
    }

    private static String textOrEmpty(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return "";
        return n.asText("");
    }

    // json-escape string safely using ObjectMapper
    private String encouragesJsonString(String s) throws IOException {
        return om.writeValueAsString(s == null ? "" : s);
    }
}
