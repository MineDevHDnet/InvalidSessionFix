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
     * MultiMC's public Microsoft OAuth application id. The refresh token in
     * MultiMC's accounts.json is issued to this public client, so the same
     * client id has to be used when refreshing it.
     */
    private static final String MULTIMC_CLIENT_ID = "499546d9-bbfe-4b9b-a086-eb3d75afb78f";

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

    public AuthResult refresh(String refreshToken) throws AuthException {
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            throw new AuthException("In MultiMC wurde kein Microsoft-Refresh-Token gefunden.");
        }

        try {
            JsonObject msa = refreshMicrosoftToken(refreshToken);
            String msaAccessToken = requiredString(msa, "access_token", "Microsoft access token");
            String newRefreshToken = optionalString(msa, "refresh_token", refreshToken);
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
        } catch (IOException ex) {
            throw new AuthException("Netzwerkfehler waehrend der Session-Reparatur.", ex);
        }
    }

    private JsonObject refreshMicrosoftToken(String refreshToken) throws IOException, AuthException {
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("client_id", MULTIMC_CLIENT_ID);
        form.put("scope", "XboxLive.signin offline_access");
        form.put("refresh_token", refreshToken);
        form.put("grant_type", "refresh_token");

        HttpUtil.Response response = HttpUtil.postForm(TOKEN_ENDPOINT, form);
        ensureSuccess(response, "Microsoft-Token konnte nicht erneuert werden");
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
