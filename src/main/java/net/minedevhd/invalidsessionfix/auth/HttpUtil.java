package net.minedevhd.invalidsessionfix.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class HttpUtil {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;

    private HttpUtil() {
    }

    static Response get(String url, Map<String, String> headers) throws IOException {
        return request("GET", url, null, null, headers);
    }

    static Response postJson(String url, JsonObject body) throws IOException {
        return request(
            "POST",
            url,
            "application/json; charset=utf-8",
            body.toString().getBytes(StandardCharsets.UTF_8),
            null
        );
    }

    static Response postForm(String url, Map<String, String> fields) throws IOException {
        StringBuilder form = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (form.length() > 0) {
                form.append('&');
            }
            form.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            form.append('=');
            form.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return request(
            "POST",
            url,
            "application/x-www-form-urlencoded; charset=utf-8",
            form.toString().getBytes(StandardCharsets.UTF_8),
            null
        );
    }

    static JsonObject parseObject(Response response) throws AuthException {
        try {
            return new JsonParser().parse(response.body).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new AuthException("Die Authentifizierungsantwort war kein gueltiges JSON.", ex);
        }
    }

    static Map<String, String> bearer(String token) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("Accept", "application/json");
        return headers;
    }

    private static Response request(
        String method,
        String url,
        String contentType,
        byte[] body,
        Map<String, String> headers
    ) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "InvalidSessionFix/1.0.0");
        connection.setRequestProperty("Accept", "application/json");

        if (contentType != null) {
            connection.setRequestProperty("Content-Type", contentType);
        }
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }

        if (body != null) {
            connection.setDoOutput(true);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(body);
            } finally {
                output.close();
            }
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = stream == null ? "" : readAll(stream);
        connection.disconnect();
        return new Response(status, responseBody);
    }

    private static String readAll(InputStream stream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        } finally {
            reader.close();
        }
        return result.toString();
    }

    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
