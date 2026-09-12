package net.minedevhd.invalidsessionfix.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Private token store owned by InvalidSessionFix.
 *
 * Important: this deliberately does not use or modify MultiMC's accounts.json.
 * MultiMC keeps its account state in memory while Minecraft is running. Consuming
 * or rotating MultiMC's refresh token from a child process can leave the launcher
 * with a stale token and make it mark the account as expired.
 */
public final class AuthTokenStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File tokenFile;

    public AuthTokenStore(File tokenFile) {
        this.tokenFile = tokenFile;
    }

    public synchronized boolean hasRefreshToken() {
        try {
            String token = readRefreshToken();
            return token != null && !token.trim().isEmpty();
        } catch (AuthException ex) {
            return false;
        }
    }

    public synchronized String readRefreshToken() throws AuthException {
        if (!tokenFile.isFile()) {
            throw new AuthException("InvalidSessionFix ist noch nicht mit Microsoft verknuepft. Nutze /sessionfix login.");
        }

        JsonObject root = readRoot();
        String token = getString(root, "microsoftRefreshToken");
        if (token == null || token.trim().isEmpty()) {
            throw new AuthException("Gespeicherter InvalidSessionFix-Login ist unvollstaendig. Nutze /sessionfix login erneut.");
        }
        return token;
    }

    public synchronized void persist(AuthResult result) throws AuthException {
        if (result == null || result.microsoftRefreshToken == null
            || result.microsoftRefreshToken.trim().isEmpty()) {
            throw new AuthException("Microsoft hat keinen Refresh-Token fuer InvalidSessionFix geliefert.");
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("profileName", result.profileName);
        root.addProperty("profileId", normalizeId(result.profileId));
        root.addProperty("microsoftRefreshToken", result.microsoftRefreshToken);
        root.addProperty("updatedAt", System.currentTimeMillis() / 1000L);
        writeAtomically(root);
    }

    public synchronized void clear() throws AuthException {
        if (!tokenFile.exists()) {
            return;
        }
        try {
            if (!tokenFile.delete()) {
                throw new IOException("delete returned false");
            }
        } catch (IOException ex) {
            throw new AuthException("InvalidSessionFix-Login konnte nicht geloescht werden.", ex);
        }
    }

    public File getTokenFile() {
        return tokenFile;
    }

    private JsonObject readRoot() throws AuthException {
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(tokenFile), StandardCharsets.UTF_8)
            );
            try {
                JsonElement parsed = new JsonParser().parse(reader);
                if (!parsed.isJsonObject()) {
                    throw new AuthException("InvalidSessionFix-Token-Datei hat kein gueltiges Objektformat.");
                }
                return parsed.getAsJsonObject();
            } finally {
                reader.close();
            }
        } catch (IOException ex) {
            throw new AuthException("InvalidSessionFix-Token-Datei konnte nicht gelesen werden.", ex);
        } catch (RuntimeException ex) {
            throw new AuthException("InvalidSessionFix-Token-Datei ist kein gueltiges JSON.", ex);
        }
    }

    private void writeAtomically(JsonObject root) throws AuthException {
        File parent = tokenFile.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new AuthException("Konfigurationsordner fuer InvalidSessionFix konnte nicht erstellt werden.");
        }

        File temp = new File(parent, tokenFile.getName() + ".tmp");
        try {
            BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)
            );
            try {
                GSON.toJson(root, writer);
            } finally {
                writer.close();
            }

            try {
                Files.move(
                    temp.toPath(),
                    tokenFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException atomicMoveFailed) {
                Files.move(
                    temp.toPath(),
                    tokenFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException ex) {
            if (temp.exists()) {
                temp.delete();
            }
            throw new AuthException("InvalidSessionFix-Token-Datei konnte nicht sicher gespeichert werden.", ex);
        }
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String normalizeId(String id) {
        return id == null ? null : id.replace("-", "").trim().toLowerCase();
    }
}
