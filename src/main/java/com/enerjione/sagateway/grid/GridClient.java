package com.enerjione.sagateway.grid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Grid SA gateway uclari. Kimlik: mevcut Grid gateway kimligi
 * ({@code X-Gateway-Token}); yeni bir kimlik sistemi yok.
 *
 * <pre>
 * GET  {base}/sa/gateway/{kod}/config  -> IED'ler + noktalar (bu gateway'e bagli olanlar)
 * POST {base}/sa/gateway/{kod}/values  -> deger partisi
 * </pre>
 */
public final class GridClient {

    /** Grid'in yanit verdigi ama kabul etmedigi istek (HTTP durum kodu ile). */
    public static final class GridHttpException extends IOException {
        public final int status;

        public GridHttpException(int status, String body) {
            super("HTTP " + status + ": " + (body.length() > 300 ? body.substring(0, 300) : body));
            this.status = status;
        }

        /**
         * Tekrar denemek anlamsiz mi (govde sema disi / cok buyuk). 400 BILEREK
         * yok: aktarim hatasi da 400 doner, veri atmak yerine tekrar denenir.
         */
        public boolean permanent() {
            return status == 413 || status == 422;
        }
    }

    /** Gonderilecek tek deger. {@code value} null + invalid = baglanti kaybi. */
    public record ValueItem(long pointId, Double value, String quality, String ts, String cause) {
    }

    /** Uygulama sonucu. */
    public record PostResult(int accepted, int rejected, JsonNode rejectedDetail) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String base;
    private final String code;
    private final String token;
    private final HttpClient http;

    public GridClient(String baseUrl, String gatewayCode, String token) {
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.code = gatewayCode;
        this.token = token;
        // HTTP/1.1 SABIT: varsayilan HTTP/2, duz http:// uzerinde h2c yukseltmesi dener;
        // uvicorn bunu desteklemez ve POST govdesi kaybolur (yerel uctan uca denemede
        // 400/422 olarak goruldu). https'te ALPN ile sorun olmaz ama sabitlemek guvenli.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + "/sa/gateway/" + code + path))
                .timeout(Duration.ofSeconds(20))
                .header("X-Gateway-Token", token)
                .header("X-Gateway-Code", code)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("Accept", "application/json");
    }

    public JsonNode fetchConfig() throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(request("/config").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new GridHttpException(r.statusCode(), r.body());
        return JSON.readTree(r.body());
    }

    public PostResult postValues(List<ValueItem> items) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode arr = body.putArray("values");
        for (ValueItem v : items) {
            ObjectNode n = arr.addObject();
            n.put("point_id", v.pointId());
            if (v.value() == null) n.putNull("value");
            else n.put("value", v.value());
            n.put("quality", v.quality());
            if (v.ts() != null) n.put("ts", v.ts());
            n.put("cause", v.cause());
        }
        HttpRequest req = request("/values")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new GridHttpException(r.statusCode(), r.body());
        JsonNode res = JSON.readTree(r.body());
        JsonNode rej = res.path("rejected");
        return new PostResult(res.path("accepted").asInt(), rej.isArray() ? rej.size() : 0, rej);
    }
}
