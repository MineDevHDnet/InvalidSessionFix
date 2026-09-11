package net.minedevhd.invalidsessionfix.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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

public final class MultiMcAccountStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File accountsFile;
    private final String username;
    private final String profileId;

    private MultiMcAccountStore(File accountsFile, String username, String profileId) {
        this.accountsFile = accountsFile;
        this.username = username;
        this.profileId = normalizeId(profileId);
    }

    public static MultiMcAccountStore locate(File minecraftDirectory, String username, String profileId)
        throws AuthException {
        File current = minecraftDirectory == null ? null : minecraftDirectory.getAbsoluteFile();

        for (int depth = 0; current != null && depth < 9; depth++) {
            File candidate = new File(current, "accounts.json");
            if (candidate.isFile() && containsMatchingMsaAccount(candidate, username, profileId)) {
                return new MultiMcAccountStore(candidate, username, profileId);
            }
            current = current.getParentFile();
        }

        throw new AuthException(
            "MultiMC accounts.json wurde nicht gefunden. Die Mod erwartet eine MultiMC-Instanz mit Microsoft-Konto."
        );
    }

    public synchronized String readRefreshToken() throws AuthException {
        JsonObject account = findAccount(readRoot(), username, profileId);
        JsonObject msa = getObject(account, "msa");
        String token = getString(msa, "refresh_token");
        if (token == null || token.trim().isEmpty()) {
            throw new AuthException("In accounts.json ist kein Microsoft-Refresh-Token fuer diesen Account gespeichert.");
        }
        return token;
    }

    public synchronized void persist(AuthResult result) throws AuthException {
        JsonObject root = readRoot();
        JsonObject account = findAccount(root, username, profileId);

        long now = System.currentTimeMillis() / 1000L;
        JsonObject msa = ensureObject(account, "msa");
        msa.addProperty("iat", now);
        msa.addProperty("exp", now + Math.max(60L, result.microsoftExpiresIn));
        msa.addProperty("token", result.microsoftAccessToken);
        msa.addProperty("refresh_token", result.microsoftRefreshToken);

        JsonObject ygg = ensureObject(account, "ygg");
        ygg.addProperty("iat", now);
        ygg.addProperty("exp", now + Math.max(60L, result.minecraftExpiresIn));
        ygg.addProperty("token", result.minecraftAccessToken);

        writeAtomically(root);
    }

    public File getAccountsFile() {
        return accountsFile;
    }

    private void writeAtomically(JsonObject root) throws AuthException {
        File parent = accountsFile.getAbsoluteFile().getParentFile();
        File temp = new File(parent, accountsFile.getName() + ".invalidsessionfix.tmp");
        File backup = new File(parent, accountsFile.getName() + ".invalidsessionfix.bak");

        try {
            Files.copy(
                accountsFile.toPath(),
                backup.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            );

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
                    accountsFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException atomicMoveFailed) {
                Files.move(
                    temp.toPath(),
                    accountsFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException ex) {
            if (temp.exists()) {
                temp.delete();
            }
            throw new AuthException("accounts.json konnte nicht sicher aktualisiert werden.", ex);
        }
    }

    private JsonObject readRoot() throws AuthException {
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(accountsFile), StandardCharsets.UTF_8)
            );
            try {
                JsonElement parsed = new JsonParser().parse(reader);
                if (!parsed.isJsonObject()) {
                    throw new AuthException("accounts.json hat kein gueltiges Objektformat.");
                }
                return parsed.getAsJsonObject();
            } finally {
                reader.close();
            }
        } catch (IOException ex) {
            throw new AuthException("accounts.json konnte nicht gelesen werden.", ex);
        } catch (RuntimeException ex) {
            throw new AuthException("accounts.json ist kein gueltiges JSON.", ex);
        }
    }

    private static boolean containsMatchingMsaAccount(File file, String username, String profileId) {
        try {
            MultiMcAccountStore store = new MultiMcAccountStore(file, username, profileId);
            findAccount(store.readRoot(), username, normalizeId(profileId));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static JsonObject findAccount(JsonObject root, String username, String profileId)
        throws AuthException {
        if (root == null || !root.has("accounts") || !root.get("accounts").isJsonArray()) {
            throw new AuthException("accounts.json enthaelt keine Account-Liste.");
        }

        JsonArray accounts = root.getAsJsonArray("accounts");
        JsonObject activeFallback = null;

        for (JsonElement element : accounts) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject account = element.getAsJsonObject();
            if (!"MSA".equalsIgnoreCase(getString(account, "type"))) {
                continue;
            }

            JsonObject profile = getObject(account, "profile");
            String candidateName = profile == null ? null : getString(profile, "name");
            String candidateId = profile == null ? null : normalizeId(getString(profile, "id"));

            if (same(candidateName, username) || same(candidateId, normalizeId(profileId))) {
                return account;
            }

            if (account.has("active") && account.get("active").isJsonPrimitive()
                && account.get("active").getAsBoolean()) {
                activeFallback = account;
            }
        }

        if (activeFallback != null) {
            return activeFallback;
        }

        throw new AuthException("Kein passendes Microsoft-Konto in MultiMC accounts.json gefunden.");
    }

    private static JsonObject ensureObject(JsonObject parent, String key) {
        JsonObject object = getObject(parent, key);
        if (object == null) {
            object = new JsonObject();
            parent.add(key, object);
        }
        return object;
    }

    private static JsonObject getObject(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(key);
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

    private static boolean same(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }
}
