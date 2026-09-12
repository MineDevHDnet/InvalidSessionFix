package net.minedevhd.invalidsessionfix.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MicrosoftSessionRefresher {
    /*
     * MultiMC's public Microsoft OAuth application id. InvalidSessionFix uses
     * the same public client for its own device-flow login, but keeps its own
     * refresh token. It never consumes MultiMC's accounts.json refresh token.
     */
    private static final String MULTIMC_CLIENT_ID = "499546d9-bbfe-4b9b-a086-eb3d75afb78f";

    private static final String DEVICE_CODE_ENDPOINT =
        "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_ENDPOINT =
        "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBOX_USER_ENDPOINT =
        "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_ENDPOINT =
        "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MINECRAFT_LOGIN_ENDPOINT =
        "https://api.minecraftservices.com/launcher/login";
    private static final String MINECRAFT_PROFILE_ENDPOINT =
        "https://api.minecraftservices.com/minecraft/profile";

    public enum Validation {
        VALID,
        INVALID,
        UNKNOWN
    }

    public static final class DeviceCode {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUri;
        public final String verificationUriComplete;
        public final long expiresInSeconds;
        public final long intervalSeconds;

        private DeviceCode(
            String deviceCode,
            String userCode,
            String verificationUri,
            String verificationUriComplete,
            long expiresInSeconds,
            long intervalSeconds
        ) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.verificationUriComplete = verificationUriComplete;
            this.expiresInSeconds = expiresInSeconds;
            this.intervalSeconds = intervalSeconds;
        }
    }

    public Validation validate(String minecraftAccessToken) {
        if (minecraftAccessToken == null || minecraftAccessToken.trim().isEmpty()) {
            return Validation.INVALID;
        }

        try {
            HttpUtil.Response response = HttpUtil.get(
                MINECRAFT_PROFILE_ENDPOINT,
                HttpUtil.bearer(minecraftAccessToken)
            );
            if (response.status == 200) {
                return Validation.VALID;
            }
            if (response.status == 401 || response.status == 403) {
                return Validation.INVALID;
            }
            return Validation.UNKNOWN;
        } catch (IOException ex) {
            return Validation.UNKNOWN;
        }
    }

    public DeviceCode beginDeviceLogin() throws AuthException {
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("client_id", MULTIMC_CLIENT_ID);
        form.put("scope", "XboxLive.signin offline_access");

        try {
            HttpUtil.Response response = HttpUtil.postForm(DEVICE_CODE_ENDPOINT, form);
            ensureSuccess(response, "Microsoft-Gerateanmeldung konnte nicht gestartet werden");
            JsonObject json = HttpUtil.parseObject(response);

            return new DeviceCode(
                requiredString(json, "device_code", "Microsoft device code"),
                requiredString(json, "user_code", "Microsoft user code"),
                requiredString(json, "verification_uri", "Microsoft verification URI"),
                optionalString(json, "verification_uri_complete", null),
                optionalLong(json, "expires_in", 900L),
                Math.max(1L, optionalLong(json, "interval", 5L))
            );
        } catch (IOException ex) {
            throw new AuthException("Netzwerkfehler beim Start der Microsoft-Anmeldung.", ex);
        }
    }

    public AuthResult completeDeviceLogin(DeviceCode code) throws AuthException {
        if (code == null || code.deviceCode == null || code.deviceCode.trim().isEmpty()) {
            throw new AuthException("Microsoft-Gerateanmeldung hat keinen gueltigen Code geliefert.");
        }

        long deadline = System.currentTimeMillis() + Math.max(60L, code.expiresInSeconds) * 1000L;
        long interval = Math.max(1L, code.intervalSeconds);

        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(interval * 1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AuthException("Microsoft-Anmeldung wurde abgebrochen.", ex);
            }

            Map<String, String> form = new LinkedHashMap<String, String>();
            form.put("client_id", MULTIMC_CLIENT_ID);
            form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            form.put("device_code", code.deviceCode);

            try {
                HttpUtil.Response response = HttpUtil.postForm(TOKEN_ENDPOINT, form);
                if (response.status >= 200 && response.status < 300) {
                    return finishMicrosoftLogin(HttpUtil.parseObject(response));
                }

                JsonObject error = safeParse(response);
                String errorCode = optionalString(error, "error", "");
                if ("authorization_pending".equalsIgnoreCase(errorCode)) {
                    continue;
                }
                if ("slow_down".equalsIgnoreCase(errorCode)) {
                    interval += 5L;
                    continue;
                }
                if ("authorization_declined".equalsIgnoreCase(errorCode)
                    || "access_denied".equalsIgnoreCase(errorCode)) {
                    throw new AuthException("Microsoft-Anmeldung wurde abgelehnt.");
                }
                if ("expired_token".equalsIgnoreCase(errorCode)) {
                    throw new AuthException("Microsoft-Anmeldecode ist abgelaufen. Nutze /sessionfix login erneut.");
                }

                ensureSuccess(response, "Microsoft-Anmeldung ist fehlgeschlagen");
            } catch (IOException ex) {
                throw new AuthException("Netzwerkfehler waehrend der Microsoft-Anmeldung.", ex);
            }
        }

        throw new AuthException("Microsoft-Anmeldecode ist abgelaufen. Nutze /sessionfix login erneut.");
    }

    public AuthResult refresh(String refreshToken) throws AuthException {
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            throw new AuthException("InvalidSessionFix hat keinen eigenen Microsoft-Refresh-Token. Nutze /sessionfix login.");
        }

        try {
            JsonObject msa = refreshMicrosoftToken(refreshToken);
            return finishMicrosoftLogin(msa);
        } catch (IOException ex) {
            throw new AuthException("Netzwerkfehler waehrend der Session-Reparatur.", ex);
        }
    }

    private AuthResult finishMicrosoftLogin(JsonObject msa) throws AuthException, IOException {
        String msaAccessToken = requiredString(msa, "access_token", "Microsoft access token");
        String newRefreshToken = requiredString(msa, "refresh_token", "Microsoft refresh token");
        long msaExpiresIn = optionalLong(msa, "expires_in", 3600L);

        XToken userToken = authenticateXboxUser(msaAccessToken);
        XToken minecraftXsts = authorizeMinecraft(userToken);
        JsonObject minecraftLogin = loginMinecraft(minecraftXsts);
        String minecraftAccessToken = requiredString(
            minecraftLogin,
            "access_token",
            "Minecraft access token"
        );
        long minecraftExpiresIn = optionalLong(minecraftLogin, "expires_in", 86400L);

        JsonObject profile = loadMinecraftProfile(minecraftAccessToken);

        AuthResult result = new AuthResult();
        result.microsoftAccessToken = msaAccessToken;
        result.microsoftRefreshToken = newRefreshToken;
        result.microsoftExpiresIn = msaExpiresIn;
        result.minecraftAccessToken = minecraftAccessToken;
        result.minecraftExpiresIn = minecraftExpiresIn;
        result.profileName = requiredString(profile, "name", "Minecraft profile name");
        result.profileId = requiredString(profile, "id", "Minecraft profile id");
        return result;
    }

    private JsonObject refreshMicrosoftToken(String refreshToken) throws IOException, AuthException {
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("client_id", MULTIMC_CLIENT_ID);
        form.put("scope", "XboxLive.signin offline_access");
        form.put("refresh_token", refreshToken);
        form.put("grant_type", "refresh_token");

        HttpUtil.Response response = HttpUtil.postForm(TOKEN_ENDPOINT, form);
        ensureSuccess(response, "InvalidSessionFix-Token konnte nicht erneuert werden");
        return HttpUtil.parseObject(response);
    }

    private XToken authenticateXboxUser(String msaAccessToken) throws IOException, AuthException {
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", "d=" + msaAccessToken);

        JsonObject request = new JsonObject();
        request.add("Properties", properties);
        request.addProperty("RelyingParty", "http://auth.xboxlive.com");
        request.addProperty("TokenType", "JWT");

        HttpUtil.Response response = HttpUtil.postJson(XBOX_USER_ENDPOINT, request);
        ensureSuccess(response, "Xbox-Authentifizierung ist fehlgeschlagen");
        return parseXToken(HttpUtil.parseObject(response));
    }

    private XToken authorizeMinecraft(XToken userToken) throws IOException, AuthException {
        JsonArray userTokens = new JsonArray();
        userTokens.add(new JsonPrimitive(userToken.token));

        JsonObject properties = new JsonObject();
        properties.addProperty("SandboxId", "RETAIL");
        properties.add("UserTokens", userTokens);

        JsonObject request = new JsonObject();
        request.add("Properties", properties);
        request.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        request.addProperty("TokenType", "JWT");

        HttpUtil.Response response = HttpUtil.postJson(XSTS_ENDPOINT, request);
        ensureSuccess(response, "Minecraft-XSTS-Autorisierung ist fehlgeschlagen");
        XToken result = parseXToken(HttpUtil.parseObject(response));

        if (!userToken.userHash.equals(result.userHash)) {
            throw new AuthException("Microsoft hat waehrend der Anmeldung einen anderen Xbox-UserHash geliefert.");
        }
        return result;
    }

    private JsonObject loginMinecraft(XToken minecraftXsts) throws IOException, AuthException {
        JsonObject request = new JsonObject();
        request.addProperty(
            "xtoken",
            "XBL3.0 x=" + minecraftXsts.userHash + ";" + minecraftXsts.token
        );
        request.addProperty("platform", "PC_LAUNCHER");

        HttpUtil.Response response = HttpUtil.postJson(MINECRAFT_LOGIN_ENDPOINT, request);
        ensureSuccess(response, "Minecraft-Access-Token konnte nicht erstellt werden");
        return HttpUtil.parseObject(response);
    }

    private JsonObject loadMinecraftProfile(String minecraftAccessToken)
        throws IOException, AuthException {
        HttpUtil.Response response = HttpUtil.get(
            MINECRAFT_PROFILE_ENDPOINT,
            HttpUtil.bearer(minecraftAccessToken)
        );
        ensureSuccess(response, "Minecraft-Profil konnte nach der Reparatur nicht verifiziert werden");
        return HttpUtil.parseObject(response);
    }

    private static XToken parseXToken(JsonObject object) throws AuthException {
        String token = requiredString(object, "Token", "Xbox token");

        JsonObject claims = object.has("DisplayClaims")
            ? object.getAsJsonObject("DisplayClaims")
            : null;
        JsonArray xui = claims != null && claims.has("xui")
            ? claims.getAsJsonArray("xui")
            : null;

        if (xui == null || xui.size() == 0 || !xui.get(0).isJsonObject()) {
            throw new AuthException("Xbox-Antwort enthaelt keinen UserHash.");
        }

        String userHash = requiredString(xui.get(0).getAsJsonObject(), "uhs", "Xbox user hash");
        return new XToken(token, userHash);
    }

    private static void ensureSuccess(HttpUtil.Response response, String message) throws AuthException {
        if (response.status >= 200 && response.status < 300) {
            return;
        }

        String suffix = "";
        try {
            JsonObject error = HttpUtil.parseObject(response);
            if (error.has("error_description")) {
                suffix = ": " + safeMessage(error.get("error_description"));
            } else if (error.has("error")) {
                suffix = ": " + safeMessage(error.get("error"));
            } else if (error.has("XErr")) {
                suffix = " (XErr " + error.get("XErr").getAsString() + ")";
            }
        } catch (Exception ignored) {
        }

        throw new AuthException(message + " (HTTP " + response.status + ")" + suffix);
    }

    private static JsonObject safeParse(HttpUtil.Response response) {
        try {
            return HttpUtil.parseObject(response);
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static String safeMessage(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        String text;
        try {
            text = value.getAsString();
        } catch (RuntimeException ex) {
            return "";
        }
        text = text.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() > 180 ? text.substring(0, 180) + "..." : text;
    }

    private static String requiredString(JsonObject object, String key, String label)
        throws AuthException {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            throw new AuthException(label + " fehlt in der Serverantwort.");
        }
        try {
            String value = object.get(key).getAsString();
            if (value == null || value.trim().isEmpty()) {
                throw new AuthException(label + " ist leer.");
            }
            return value;
        } catch (RuntimeException ex) {
            throw new AuthException(label + " hat ein ungueltiges Format.", ex);
        }
    }

    private static String optionalString(JsonObject object, String key, String fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString()
                : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static long optionalLong(JsonObject object, String key, long fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsLong()
                : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static final class XToken {
        private final String token;
        private final String userHash;

        private XToken(String token, String userHash) {
            this.token = token;
            this.userHash = userHash;
        }
    }
}
