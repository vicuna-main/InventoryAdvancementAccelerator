package dev.invadvopt;

import dev.invadvopt.command.InvAdvOptCommand;
import dev.invadvopt.config.InvAdvOptConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(InvAdvOpt.MOD_ID)
public final class InvAdvOpt {
    public static final String MOD_ID = "invadvopt";
    public static final InventoryAdvancementRuntime RUNTIME = new InventoryAdvancementRuntime();

    public InvAdvOpt(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, InvAdvOptConfig.SPEC);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        RUNTIME.startupSelfCheck();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        RUNTIME.clear();
    }

    @SubscribeEvent
    public void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD && event.shouldUpdateStaticData()) {
            RUNTIME.tagsUpdated();
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        InvAdvOptCommand.register(event.getDispatcher());
    }
}
