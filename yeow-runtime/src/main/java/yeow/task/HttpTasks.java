package yeow.task;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class HttpTasks {
    private static final Gson gson = new Gson();
    private static final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public static Object execute(String op, JsonObject p) throws Exception {
        return switch (op) {
            case "request" -> request(p);
            default -> throw new IllegalArgumentException("Unknown http op: " + op);
        };
    }

    private static Map<String, Object> request(JsonObject p) throws Exception {
        var method = p.has("method") ? p.get("method").getAsString().toUpperCase() : "GET";
        var url = p.get("url").getAsString();
        var timeout = p.has("timeout") ? p.get("timeout").getAsInt() : 10000;

        var builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(timeout));

        // Headers
        if (p.has("headers")) {
            var headers = p.getAsJsonObject("headers");
            for (var key : headers.keySet()) {
                builder.header(key, headers.get(key).getAsString());
            }
        }

        // Body
        if (!method.equals("GET") && !method.equals("HEAD") && p.has("body")) {
            var body = p.get("body").getAsString();
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        var result = new LinkedHashMap<String, Object>();
        result.put("status", response.statusCode());
        result.put("statusText", "");
        result.put("body", response.body());

        var respHeaders = new LinkedHashMap<String, String>();
        for (var entry : response.headers().map().entrySet()) {
            respHeaders.put(entry.getKey(), String.join(", ", entry.getValue()));
        }
        result.put("headers", respHeaders);

        return result;
    }
}
