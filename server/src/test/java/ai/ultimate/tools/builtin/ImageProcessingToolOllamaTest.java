/* ABOUTME: Opt-in local Ollama -> Spring callback -> native image round trip. */
package ai.ultimate.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.support.ToolCallbacks;
import ai.ultimate.ai.provider.OllamaProvider;
import ai.ultimate.tools.ToolRegistry;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.web.reactive.function.client.WebClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ULTIMATE_IMAGE_OLLAMA_TEST", matches = "true")
class ImageProcessingToolOllamaTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test @Timeout(180)
    void callsRealLocalModelThenExecutesItsTypedToolArguments() throws Exception {
        String endpoint = System.getenv().getOrDefault("ULTIMATE_IMAGE_OLLAMA_URL", "http://localhost:11434");
        URI uri = URI.create(endpoint);
        assertThat(List.of("localhost", "127.0.0.1", "host.docker.internal")).contains(uri.getHost());
        assertThat(uri.getScheme()).isEqualTo("http");
        Path root = Files.createDirectory(temp.resolve("managed"));
        Path workspace = Files.createDirectory(root.resolve("job"));
        Files.createDirectory(workspace.resolve("results"));
        ImageIO.write(new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB), "png",
                workspace.resolve("input.png").toFile());
        String binary = Files.isExecutable(Path.of("/usr/bin/magick")) ? "/usr/bin/magick" : "/usr/bin/convert";
        var tool = new ImageProcessingTool(root, new ImageProcessingTool.ProcessRunner(), binary);
        var callback = ToolCallbacks.from(tool)[0];
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("workspacePath", workspace.toString());
        arguments.put("inputPaths", List.of("input.png"));
        arguments.put("outputDirectory", "results");
        arguments.put("outputPrefix", "ollama");
        arguments.put("outputFormat", "png");
        arguments.put("width", 8);
        arguments.put("height", 6);
        arguments.put("crop", "");
        arguments.put("brightness", 100);
        arguments.put("saturation", 100);
        arguments.put("hue", 100);
        arguments.put("watermarkPath", "");
        arguments.put("stripMetadata", true);
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "Call processImages exactly once with these exact "
                + "arguments, including empty strings. Do not answer instead of calling it: "
                + json.writeValueAsString(arguments) + " /no_think"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", System.getenv().getOrDefault("ULTIMATE_IMAGE_OLLAMA_MODEL", "qwen3:4b"));
        request.put("stream", false);
        request.put("think", false);
        request.put("options", Map.of("temperature", 0.7, "top_p", 0.8, "top_k", 20,
                "num_predict", 2048, "num_ctx", 8192, "num_thread", 2));
        request.put("messages", messages);
        request.put("tools", List.of(Map.of("type", "function", "function", Map.of(
                "name", callback.getToolDefinition().name(),
                "description", callback.getToolDefinition().description(),
                "parameters", json.readTree(callback.getToolDefinition().inputSchema())))));
        JsonNode first = chat(endpoint, request);
        System.out.println("Local model tool calls: " + first.path("message").path("tool_calls"));
        JsonNode calls = first.path("message").path("tool_calls");
        assertThat(calls.isArray()).as("Actual model must request the image tool").isTrue();
        assertThat(calls.size()).isEqualTo(1);
        JsonNode function = calls.get(0).path("function");
        assertThat(function.path("name").asText()).isEqualTo("processImages");
        String result = callback.call(function.path("arguments").toString());
        assertThat(result).contains("Image processing completed", "results/ollama-01.png");
        BufferedImage image = ImageIO.read(workspace.resolve("results/ollama-01.png").toFile());
        assertThat(image.getWidth()).isEqualTo(8);
        assertThat(image.getHeight()).isEqualTo(6);
        messages.add(json.convertValue(first.path("message"), Map.class));
        messages.add(Map.of("role", "tool", "tool_name", "processImages", "content", result));
        request.remove("tools");
        JsonNode last = chat(endpoint, request);
        assertThat(last.path("message").path("content").asText()).isNotBlank();
        System.out.println("Local Ollama image round trip passed: " + request.get("model")
                + "; genuine model tool call; Spring callback; decoded 8x6 PNG.");
    }

    @Test @Timeout(180)
    void executesNativeImageToolThroughExistingStreamingProvider() throws Exception {
        String endpoint = System.getenv().getOrDefault("ULTIMATE_IMAGE_OLLAMA_URL", "http://localhost:11434");
        assertThat(List.of("localhost", "127.0.0.1", "host.docker.internal")).contains(URI.create(endpoint).getHost());
        String modelName = System.getenv().getOrDefault("ULTIMATE_IMAGE_OLLAMA_MODEL", "qwen3:4b");
        Path root = Files.createDirectory(temp.resolve("managed"));
        Path workspace = Files.createDirectory(root.resolve("job"));
        Files.createDirectory(workspace.resolve("results"));
        ImageIO.write(new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB), "png",
                workspace.resolve("input.png").toFile());
        String binary = Files.isExecutable(Path.of("/usr/bin/magick")) ? "/usr/bin/magick" : "/usr/bin/convert";
        var tool = new ImageProcessingTool(root, new ImageProcessingTool.ProcessRunner(), binary);
        var model = OllamaChatModel.builder().ollamaApi(OllamaApi.builder().baseUrl(endpoint).build())
                .defaultOptions(OllamaChatOptions.builder().model(modelName).temperature(0.7)
                        .topP(0.8).topK(20).numCtx(8192).numPredict(2048).numThread(2)
                        .disableThinking().build()).build();
        var provider = new OllamaProvider(model, WebClient.builder(), new ToolRegistry(List.of(tool)), modelName, endpoint);
        String prompt = "Call processImages once to resize input.png to 8x6. Use precisely these arguments: "
                + "{\"workspacePath\":\"" + workspace + "\",\"inputPaths\":[\"input.png\"],"
                + "\"outputDirectory\":\"results\",\"outputPrefix\":\"stream\",\"outputFormat\":\"png\","
                + "\"width\":8,\"height\":6,\"crop\":\"\",\"brightness\":100,\"saturation\":100,"
                + "\"hue\":100,\"watermarkPath\":\"\",\"stripMetadata\":true} /no_think";
        List<String> tokens = provider.streamChat(new Prompt(prompt)).collectList().block(Duration.ofSeconds(150));
        assertThat(tokens).isNotEmpty();
        Path result = workspace.resolve("results/stream-01.png");
        assertThat(result).isRegularFile();
        BufferedImage image = ImageIO.read(result.toFile());
        assertThat(image.getWidth()).isEqualTo(8);
        assertThat(image.getHeight()).isEqualTo(6);
        System.out.println("Existing OllamaProvider streaming image round trip passed: " + modelName
                + "; native output decoded as 8x6 PNG.");
    }

    private JsonNode chat(String endpoint, Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(endpoint + "/api/chat"))
                .timeout(Duration.ofSeconds(75)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
