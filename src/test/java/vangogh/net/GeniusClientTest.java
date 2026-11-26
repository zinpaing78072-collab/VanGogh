package vangogh.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeniusClientTest {

    @Test
    void defaultConstructorUsesEnvironmentTokenOrFallback() {
        String configuredToken = System.getenv("GENIUS_ACCESS_TOKEN");
        String expected = configuredToken == null || configuredToken.isBlank()
                ? GeniusClient.DEFAULT_ACCESS_TOKEN
                : configuredToken.trim();

        assertEquals(expected, new GeniusClient().getAccessToken());
    }

    /**
     * Trending must read the website chart route: the documented API's
     * /charts/songs answers 403 "Action forbidden for current scope" for the
     * client tokens this app carries, which left the trending grid empty.
     */
    @Test
    void chartUrlReadsTheWebsiteChartRoute() {
        String url = GeniusClient.chartUrl();

        assertTrue(url.startsWith("https://genius.com/api/songs/chart?"), url);
        assertTrue(url.contains("per_page=20"), url);
        assertTrue(url.contains("time_period=day"), url);
        assertFalse(url.contains("api.genius.com"), url);
    }

    @Test
    void onlyARealChartPayloadCountsAsSuccess() {
        assertTrue(GeniusClient.isChartPayload(
                new ApiResponse(200, "{\"response\":{\"chart_items\":[]}}", null)));

        // A Cloudflare challenge page or an API error is a 2xx/4xx body without the key.
        assertFalse(GeniusClient.isChartPayload(
                new ApiResponse(200, "<html>Just a moment...</html>", null)));
        assertFalse(GeniusClient.isChartPayload(
                new ApiResponse(403, "{\"meta\":{\"status\":403}}", null)));
        assertFalse(GeniusClient.isChartPayload(new ApiResponse(200, null, null)));
        assertFalse(GeniusClient.isChartPayload(null));
    }
}
