package com.entertainment.tester;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class TmdbClient {
    private static final String BASE = "https://api.themoviedb.org/3";
    private static final String IMAGE_BASE = "https://image.tmdb.org/t/p/w500";
    private final String apiKey;
    private final HttpClient http = HttpClient.newHttpClient();

    public TmdbClient(String apiKey) {
        this.apiKey = apiKey;
    }

    public ApiResponse searchMovie(String query) throws Exception {
        String url = BASE + "/search/movie?api_key=" + apiKey
                + "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&language=en-US&page=1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new ApiResponse(response.statusCode(), response.body(), null);
    }

    public ApiResponse trending() throws Exception {
        String url = BASE + "/trending/movie/week?api_key=" + apiKey
                + "&language=en-US";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new ApiResponse(response.statusCode(), response.body(), null);
    }

    public static String posterPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return IMAGE_BASE + path;
    }

    public static void summarize(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int total = root.get("total_results").getAsInt();
            System.out.println("  TMDB total_results: " + total);
            JsonArray results = root.getAsJsonArray("results");
            int limit = Math.min(3, results.size());
            for (int i = 0; i < limit; i++) {
                JsonObject m = results.get(i).getAsJsonObject();
                String title = m.has("title") ? m.get("title").getAsString() : "N/A";
                String date = m.has("release_date") ? m.get("release_date").getAsString() : "N/A";
                System.out.println("   - " + title + " (" + date + ")");
            }
        } catch (Exception e) {
            System.out.println("  TMDB parse error: " + e.getMessage());
        }
    }
}