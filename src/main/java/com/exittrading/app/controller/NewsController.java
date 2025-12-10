package com.exittrading.app.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private static final Logger log = LoggerFactory.getLogger(NewsController.class);
    private static final String NEWS_URL = "https://api.tradient.org/v1/api/market/news";
    private final HttpClient client = HttpClient.newHttpClient();

    @GetMapping
    public ResponseEntity<String> latestNews() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(NEWS_URL))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return ResponseEntity.ok(resp.body());
            }
            log.warn("News fetch failed status={}", resp.statusCode());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"status\":" + resp.statusCode() + ",\"message\":\"news fetch failed\"}");
        } catch (Exception ex) {
            log.warn("News fetch error: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"status\":502,\"message\":\"news fetch error\"}");
        }
    }
}
