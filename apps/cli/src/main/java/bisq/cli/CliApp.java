package bisq.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

@Slf4j
public class CliApp {
    public static void main(String[] args) {
        fetchVersion();
    }

    // Prototype demo
    private static void fetchVersion() {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:2141/api/v1/version/get/"))
                    .GET()
                    .build();
            for (int i = 0; i < 1; i++) {
                HttpResponse<String> response = ((HttpClient) client).send(request, HttpResponse.BodyHandlers.ofString());
                String responseBody = response.body();
                ObjectMapper mapper = new ObjectMapper();
                Map<String, String> responseMap = mapper.readValue(responseBody, Map.class);
                log.info("Version: {}", responseMap.get("version"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
