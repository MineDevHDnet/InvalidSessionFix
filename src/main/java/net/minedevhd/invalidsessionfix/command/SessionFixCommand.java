package net.minedevhd.invalidsessionfix.command;

import net.minedevhd.invalidsessionfix.session.SessionController;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class SessionFixCommand extends CommandBase {
    private final SessionController controller;

    public SessionFixCommand(SessionController controller) {
        this.controller = controller;
    }

    @Override
    public String getCommandName() {
        return "sessionfix";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/sessionfix <status|login|logout|check|repair|auto|reconnect>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sendStatus(sender);
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if ("login".equals(action) || "link".equals(action)) {
            sender.addChatMessage(prefix("§7Microsoft-Verknuepfung wird gestartet..."));
            controller.requestLogin();
            return;
        }

        if ("logout".equals(action) || "unlink".equals(action)) {
            sender.addChatMessage(prefix("§7InvalidSessionFix-Verknuepfung wird geloescht..."));
            controller.requestLogout();
            return;
        }

        if ("check".equals(action)) {
            sender.addChatMessage(prefix("§7Session-Pruefung gestartet..."));
            controller.requestValidation(false);
            return;
        }

        if ("repair".equals(action) || "now".equals(action)) {
            if (!controller.isLinked()) {
                sender.addChatMessage(prefix("§eNoch nicht verknuepft. Fuehre zuerst /sessionfix login aus."));
                return;
            }
            sender.addChatMessage(prefix("§7Erzwungene Session-Reparatur gestartet..."));
            controller.requestForcedRepair(false);
            return;
        }

        if ("auto".equals(action)) {
            boolean enabled = args.length < 2
                ? !controller.isAutoRepair()
                : parseBoolean(args[1], controller.isAutoRepair());
            controller.setAutoRepair(enabled);
            sender.addChatMessage(prefix(
                enabled ? "§aAutomatische Reparatur aktiviert." : "§eAutomatische Reparatur deaktiviert."
            ));
            return;
        }

        if ("reconnect".equals(action)) {
            boolean enabled = args.length < 2
                ? !controller.isAutoReconnect()
                : parseBoolean(args[1], controller.isAutoReconnect());
            controller.setAutoReconnect(enabled);
            sender.addChatMessage(prefix(
                enabled ? "§aAuto-Reconnect aktiviert." : "§eAuto-Reconnect deaktiviert."
            ));
            return;
        }

        sender.addChatMessage(prefix("§c" + getCommandUsage(sender)));
    }

    private void sendStatus(ICommandSender sender) {
        sender.addChatMessage(prefix("§fStatus: §7" + controller.getStatus()));
        sender.addChatMessage(prefix(
            "§fMicrosoft-Link: " + (controller.isLinked() ? "§aaktiv" : "§cnicht eingerichtet")
        ));
        sender.addChatMessage(prefix(
            "§fAuto-Fix: " + (controller.isAutoRepair() ? "§aan" : "§caus")
                + " §8| §fReconnect: " + (controller.isAutoReconnect() ? "§aan" : "§caus")
        ));

        long lastSuccess = controller.getLastSuccessAt();
        if (lastSuccess > 0L) {
            String formatted = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date(lastSuccess));
            sender.addChatMessage(prefix("§fLetzte Reparatur: §7" + formatted));
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if ("on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value)
            || "an".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("off".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)
            || "aus".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        return fallback;
    }

    private static ChatComponentText prefix(String message) {
        return new ChatComponentText("§8[§bInvalidSessionFix§8] §7" + message);
    }
}
