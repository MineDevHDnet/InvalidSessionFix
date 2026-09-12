package net.minedevhd.invalidsessionfix.session;

import net.minedevhd.invalidsessionfix.auth.AuthException;
import net.minedevhd.invalidsessionfix.auth.AuthResult;
import net.minedevhd.invalidsessionfix.auth.AuthTokenStore;
import net.minedevhd.invalidsessionfix.auth.MicrosoftSessionRefresher;
import net.minedevhd.invalidsessionfix.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.Session;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.Desktop;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SessionController {
    private static final Logger LOGGER = LogManager.getLogger("InvalidSessionFix");

    private final ModConfig config;
    private final MicrosoftSessionRefresher refresher = new MicrosoftSessionRefresher();
    private final AuthTokenStore tokenStore;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean repairInProgress = new AtomicBoolean(false);

    private volatile String status = "Bereit";
    private volatile long lastSuccessAt;
    private volatile ServerData lastServerData;
    private boolean disconnectScreenSeen;

    public SessionController(ModConfig config) {
        this.config = config;
        File tokenFile = new File(
            Minecraft.getMinecraft().mcDataDir,
            "config/invalidsessionfix-auth.json"
        );
        this.tokenStore = new AuthTokenStore(tokenFile);
        this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "InvalidSessionFix-Auth");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public void start() {
        if (!tokenStore.hasRefreshToken()) {
            setStatus("Einmalige Verknuepfung noetig: /sessionfix login");
        }

        executor.scheduleWithFixedDelay(
            new Runnable() {
                @Override
                public void run() {
                    if (config.isAutoRepair() && tokenStore.hasRefreshToken()) {
                        validateAndRepair(false, false, false);
                    }
                }
            },
            config.getInitialDelaySeconds(),
            config.getCheckIntervalSeconds(),
            TimeUnit.SECONDS
        );
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        ServerData current = minecraft.getCurrentServerData();
        if (current != null) {
            lastServerData = current;
        }

        boolean disconnected = minecraft.currentScreen instanceof GuiDisconnected;
        if (disconnected && !disconnectScreenSeen && config.isAutoRepair()) {
            disconnectScreenSeen = true;
            if (tokenStore.hasRefreshToken()) {
                requestValidation(true);
            } else {
                setStatus("Nicht verknuepft: /sessionfix login");
            }
        } else if (!disconnected) {
            disconnectScreenSeen = false;
        }
    }

    public void requestValidation(final boolean reconnectAfterRepair) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                validateAndRepair(reconnectAfterRepair, false, true);
            }
        });
    }

    public void requestForcedRepair(final boolean reconnectAfterRepair) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                validateAndRepair(reconnectAfterRepair, true, true);
            }
        });
    }

    public void requestLogin() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                loginInteractive();
            }
        });
    }

    public void requestLogout() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    tokenStore.clear();
                    setStatus("Microsoft-Verknuepfung geloescht");
                    notifyClient("§aInvalidSessionFix-Verknuepfung wurde geloescht. MultiMC wurde nicht veraendert.");
                } catch (AuthException ex) {
                    setStatus("Fehler: " + ex.getMessage());
                    notifyClient("§c" + ex.getMessage());
                }
            }
        });
    }

    private void loginInteractive() {
        if (!repairInProgress.compareAndSet(false, true)) {
            notifyClient("§eEine Session-Aktion laeuft bereits.");
            return;
        }

        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            Session session = minecraft.getSession();
            if (session == null) {
                throw new AuthException("Keine Minecraft-Session vorhanden.");
            }

            setStatus("Microsoft-Verknuepfung wird gestartet...");
            MicrosoftSessionRefresher.DeviceCode code = refresher.beginDeviceLogin();

            notifyClient("§fMicrosoft-Anmeldung: §b" + code.verificationUri);
            notifyClient("§fCode: §e" + code.userCode + " §7(Browser wird geoeffnet)");
            openBrowser(code.verificationUriComplete != null
                ? code.verificationUriComplete
                : code.verificationUri);

            setStatus("Warte auf Microsoft-Anmeldung...");
            AuthResult result = refresher.completeDeviceLogin(code);

            if (!sameAccount(session, result)) {
                throw new AuthException(
                    "Der angemeldete Microsoft-Account passt nicht zum aktuell gestarteten Minecraft-Account."
                );
            }

            tokenStore.persist(result);
            replaceSessionOnClientThread(minecraft, session, result.minecraftAccessToken);
            lastSuccessAt = System.currentTimeMillis();
            setStatus("Microsoft-Verknuepfung aktiv");
            notifyClient("§aMicrosoft-Verknuepfung gespeichert. MultiMC accounts.json bleibt unangetastet.");
            LOGGER.info(
                "InvalidSessionFix private auth linked for {}. Token store: {}",
                session.getUsername(),
                tokenStore.getTokenFile().getAbsolutePath()
            );
        } catch (AuthException ex) {
            setStatus("Fehler: " + ex.getMessage());
            LOGGER.warn("InvalidSessionFix login failed: {}", ex.getMessage());
            notifyClient("§cMicrosoft-Verknuepfung fehlgeschlagen: §7" + ex.getMessage());
        } catch (Throwable ex) {
            setStatus("Unerwarteter Fehler: " + ex.getClass().getSimpleName());
            LOGGER.error("Unexpected InvalidSessionFix login failure", ex);
            notifyClient("§cUnerwarteter Fehler bei der Microsoft-Verknuepfung. Siehe Log.");
        } finally {
            repairInProgress.set(false);
        }
    }

    private void validateAndRepair(
        boolean reconnectAfterRepair,
        boolean forceRefresh,
        boolean userVisible
    ) {
        if (!repairInProgress.compareAndSet(false, true)) {
            if (userVisible) {
                notifyClient("§eEine Session-Pruefung laeuft bereits.");
            }
            return;
        }

        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            Session session = minecraft.getSession();

            if (session == null) {
                setStatus("Keine Minecraft-Session vorhanden");
                return;
            }

            if (!forceRefresh) {
                setStatus("Session wird geprueft...");
                MicrosoftSessionRefresher.Validation validation = refresher.validate(session.getToken());

                if (validation == MicrosoftSessionRefresher.Validation.VALID) {
                    setStatus("Session ist gueltig");
                    if (userVisible) {
                        notifyClient("§aSession ist gueltig.");
                    }
                    return;
                }

                if (validation == MicrosoftSessionRefresher.Validation.UNKNOWN) {
                    setStatus("Session-Pruefung nicht moeglich (Netzwerk/API)");
                    if (userVisible) {
                        notifyClient("§eSession konnte gerade nicht verifiziert werden. Keine Aenderung vorgenommen.");
                    }
                    return;
                }
            }

            if (!tokenStore.hasRefreshToken()) {
                setStatus("Nicht verknuepft: /sessionfix login");
                if (userVisible) {
                    notifyClient("§eEinmal /sessionfix login ausfuehren. MultiMC-Tokens werden ab v1.1 nicht mehr verwendet.");
                }
                return;
            }

            setStatus("Session wird repariert...");
            String refreshToken = tokenStore.readRefreshToken();
            AuthResult result = refresher.refresh(refreshToken);

            if (!sameAccount(session, result)) {
                throw new AuthException(
                    "Der erneuerte Microsoft-Account passt nicht zur aktuell gestarteten Minecraft-Session."
                );
            }

            // Persist only our own rotated token. Never read/write MultiMC accounts.json.
            tokenStore.persist(result);
            replaceSessionOnClientThread(minecraft, session, result.minecraftAccessToken);

            lastSuccessAt = System.currentTimeMillis();
            setStatus("Session erfolgreich repariert");
            LOGGER.info(
                "Minecraft session repaired for {} using InvalidSessionFix private token store.",
                session.getUsername()
            );
            notifyClient("§aSession automatisch erneuert.");

            if (reconnectAfterRepair && config.isAutoReconnect()) {
                reconnectToLastServer();
            }
        } catch (AuthException ex) {
            setStatus("Fehler: " + ex.getMessage());
            LOGGER.warn("Session repair failed: {}", ex.getMessage());
            if (userVisible) {
                notifyClient("§cSession-Reparatur fehlgeschlagen: §7" + ex.getMessage());
            }
        } catch (Throwable ex) {
            setStatus("Unerwarteter Fehler: " + ex.getClass().getSimpleName());
            LOGGER.error("Unexpected session repair failure", ex);
            if (userVisible) {
                notifyClient("§cUnerwarteter Fehler bei der Session-Reparatur. Siehe Log.");
            }
        } finally {
            repairInProgress.set(false);
        }
    }

    private static boolean sameAccount(Session current, AuthResult result) {
        String currentName = current.getUsername();
        String currentId = normalizeId(current.getPlayerID());
        String resultName = result.profileName;
        String resultId = normalizeId(result.profileId);

        boolean nameMatches = currentName != null && resultName != null
            && currentName.equalsIgnoreCase(resultName);
        boolean idMatches = currentId != null && resultId != null
            && currentId.equalsIgnoreCase(resultId);

        return nameMatches || idMatches;
    }

    private static String normalizeId(String value) {
        return value == null ? null : value.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private void replaceSessionOnClientThread(
        final Minecraft minecraft,
        final Session previous,
        final String accessToken
    ) throws AuthException {
        final Session replacement = new Session(
            previous.getUsername(),
            previous.getPlayerID(),
            accessToken,
            "mojang"
        );

        try {
            Future<?> future = minecraft.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    setSessionField(minecraft, replacement);
                }
            });
            future.get(5L, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new AuthException("Minecraft-Session konnte zur Laufzeit nicht ersetzt werden.", ex);
        }
    }

    private static void setSessionField(Minecraft minecraft, Session replacement) {
        try {
            Field sessionField = ReflectionHelper.findField(
                Minecraft.class,
                "session",
                "field_71449_j"
            );
            sessionField.setAccessible(true);

            if (Modifier.isFinal(sessionField.getModifiers())) {
                try {
                    Field modifiersField = Field.class.getDeclaredField("modifiers");
                    modifiersField.setAccessible(true);
                    modifiersField.setInt(
                        sessionField,
                        sessionField.getModifiers() & ~Modifier.FINAL
                    );
                } catch (ReflectiveOperationException ignored) {
                    // Java 8 exposes Field#modifiers. If a launcher hides it, Field#set is still attempted below.
                }
            }

            sessionField.set(minecraft, replacement);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not replace Minecraft.session", ex);
        }
    }

    private void reconnectToLastServer() {
        final ServerData server = lastServerData;
        if (server == null) {
            notifyClient("§eSession ist repariert, aber es ist kein letzter Server fuer Reconnect bekannt.");
            return;
        }

        executor.schedule(new Runnable() {
            @Override
            public void run() {
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        Minecraft minecraft = Minecraft.getMinecraft();
                        minecraft.displayGuiScreen(
                            new GuiConnecting(
                                new GuiMultiplayer(new GuiMainMenu()),
                                minecraft,
                                server
                            )
                        );
                    }
                });
            }
        }, 1200L, TimeUnit.MILLISECONDS);
    }

    private static void openBrowser(String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception ex) {
            LOGGER.debug("Could not open Microsoft verification URL automatically", ex);
        }
    }

    private void notifyClient(final String message) {
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                Minecraft minecraft = Minecraft.getMinecraft();
                if (minecraft.thePlayer != null) {
                    minecraft.thePlayer.addChatMessage(
                        new ChatComponentText("§8[§bInvalidSessionFix§8] §7" + message)
                    );
                } else {
                    LOGGER.info("[InvalidSessionFix] {}", stripFormatting(message));
                }
            }
        });
    }

    private static String stripFormatting(String value) {
        return value == null ? "" : value.replaceAll("§.", "");
    }

    private void setStatus(String value) {
        status = value;
    }

    public String getStatus() {
        return status;
    }

    public long getLastSuccessAt() {
        return lastSuccessAt;
    }

    public boolean isRepairInProgress() {
        return repairInProgress.get();
    }

    public boolean isAutoRepair() {
        return config.isAutoRepair();
    }

    public boolean isAutoReconnect() {
        return config.isAutoReconnect();
    }

    public boolean isLinked() {
        return tokenStore.hasRefreshToken();
    }

    public void setAutoRepair(boolean enabled) {
        config.setAutoRepair(enabled);
        setStatus(enabled ? "Automatische Reparatur aktiviert" : "Automatische Reparatur deaktiviert");
    }

    public void setAutoReconnect(boolean enabled) {
        config.setAutoReconnect(enabled);
        setStatus(enabled ? "Auto-Reconnect aktiviert" : "Auto-Reconnect deaktiviert");
    }
}
