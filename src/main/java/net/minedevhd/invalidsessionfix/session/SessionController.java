package net.minedevhd.invalidsessionfix.session;

import net.minedevhd.invalidsessionfix.auth.AuthException;
import net.minedevhd.invalidsessionfix.auth.AuthResult;
import net.minedevhd.invalidsessionfix.auth.MicrosoftSessionRefresher;
import net.minedevhd.invalidsessionfix.auth.MultiMcAccountStore;
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

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SessionController {
    private static final Logger LOGGER = LogManager.getLogger("InvalidSessionFix");

    private final ModConfig config;
    private final MicrosoftSessionRefresher refresher = new MicrosoftSessionRefresher();
    private final ScheduledExecutorService executor;
    private final AtomicBoolean repairInProgress = new AtomicBoolean(false);

    private volatile String status = "Bereit";
    private volatile long lastSuccessAt;
    private volatile ServerData lastServerData;
    private boolean disconnectScreenSeen;

    public SessionController(ModConfig config) {
        this.config = config;
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
        executor.scheduleWithFixedDelay(
            new Runnable() {
                @Override
                public void run() {
                    if (config.isAutoRepair()) {
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
            requestValidation(true);
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

            setStatus("Session wird repariert...");
            MultiMcAccountStore store = MultiMcAccountStore.locate(
                minecraft.mcDataDir,
                session.getUsername(),
                session.getPlayerID()
            );

            String refreshToken = store.readRefreshToken();
            AuthResult result = refresher.refresh(refreshToken);

            if (!sameAccount(session, result)) {
                throw new AuthException(
                    "Der erneuerte Microsoft-Account passt nicht zur aktuell gestarteten Minecraft-Session."
                );
            }

            store.persist(result);
            replaceSession(minecraft, session, result.minecraftAccessToken);

            lastSuccessAt = System.currentTimeMillis();
            setStatus("Session erfolgreich repariert");
            LOGGER.info(
                "Minecraft session repaired for {}. MultiMC account store: {}",
                session.getUsername(),
                store.getAccountsFile().getAbsolutePath()
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

    private void replaceSession(
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
            ReflectionHelper.setPrivateValue(
                Minecraft.class,
                minecraft,
                replacement,
                "session",
                "field_71449_j"
            );
        } catch (RuntimeException ex) {
            throw new AuthException("Minecraft-Session konnte zur Laufzeit nicht ersetzt werden.", ex);
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

    public void setAutoRepair(boolean enabled) {
        config.setAutoRepair(enabled);
        setStatus(enabled ? "Automatische Reparatur aktiviert" : "Automatische Reparatur deaktiviert");
    }

    public void setAutoReconnect(boolean enabled) {
        config.setAutoReconnect(enabled);
        setStatus(enabled ? "Auto-Reconnect aktiviert" : "Auto-Reconnect deaktiviert");
    }
}
