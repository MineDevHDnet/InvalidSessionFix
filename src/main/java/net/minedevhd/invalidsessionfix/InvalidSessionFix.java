package net.minedevhd.invalidsessionfix;

import net.minedevhd.invalidsessionfix.command.SessionFixCommand;
import net.minedevhd.invalidsessionfix.config.ModConfig;
import net.minedevhd.invalidsessionfix.session.SessionController;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

@Mod(
    modid = InvalidSessionFix.MOD_ID,
    name = InvalidSessionFix.NAME,
    version = InvalidSessionFix.VERSION,
    clientSideOnly = true,
    acceptedMinecraftVersions = "[1.8.9]"
)
public final class InvalidSessionFix {
    public static final String MOD_ID = "invalidsessionfix";
    public static final String NAME = "InvalidSessionFix";
    public static final String VERSION = "1.1.0";

    private ModConfig config;
    private SessionController controller;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        config = new ModConfig(event.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        controller = new SessionController(config);
        MinecraftForge.EVENT_BUS.register(controller);
        FMLCommonHandler.instance().bus().register(controller);
        ClientCommandHandler.instance.registerCommand(new SessionFixCommand(controller));
        controller.start();
    }
}
