package valandur.webapi;

import com.google.inject.Inject;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.asset.Asset;
import org.spongepowered.api.command.CommandManager;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.achievement.GrantAchievementEvent;
import org.spongepowered.api.event.command.SendCommandEvent;
import org.spongepowered.api.event.entity.DestructEntityEvent;
import org.spongepowered.api.event.entity.SpawnEntityEvent;
import org.spongepowered.api.event.entity.living.humanoid.player.KickPlayerEvent;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.game.GameReloadEvent;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.game.state.GameStartedServerEvent;
import org.spongepowered.api.event.game.state.GameStoppedServerEvent;
import org.spongepowered.api.event.item.inventory.InteractInventoryEvent;
import org.spongepowered.api.event.message.MessageChannelEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.event.user.BanUserEvent;
import org.spongepowered.api.event.world.LoadWorldEvent;
import org.spongepowered.api.event.world.UnloadWorldEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.scheduler.SpongeExecutorService;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.util.Tuple;
import valandur.webapi.cache.*;
import valandur.webapi.command.*;
import valandur.webapi.handlers.AuthHandler;
import valandur.webapi.handlers.RateLimitHandler;
import valandur.webapi.handlers.WebAPIErrorHandler;
import valandur.webapi.hooks.WebHooks;
import valandur.webapi.json.JsonConverter;
import valandur.webapi.misc.Util;
import valandur.webapi.misc.WebAPICommandSource;
import valandur.webapi.misc.JettyLogger;
import valandur.webapi.servlets.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Plugin(
        id = WebAPI.ID,
        version = WebAPI.VERSION,
        name = WebAPI.NAME,
        url = WebAPI.URL,
        description = WebAPI.DESCRIPTION,
        authors = {
                "Valandur"
        }
)
public class WebAPI {

    public static final String ID = "webapi";
    public static final String NAME = "Web-API";
    public static final String VERSION = "@version@";
    public static final String DESCRIPTION = "Access Minecraft through a Web API";
    public static final String URL = "https://github.com/Valandur/Web-API";

    private static WebAPI instance;
    public static WebAPI getInstance() {
        return WebAPI.instance;
    }

    public static SpongeExecutorService syncExecutor;

    private Reflections reflections;
    public Reflections getReflections() { return this.reflections; }

    @Inject
    private Logger logger;
    public Logger getLogger() {
        return this.logger;
    }

    @Inject
    @ConfigDir(sharedRoot = false)
    private Path configPath;

    @Inject
    private PluginContainer container;
    public PluginContainer getContainer() { return this.container; }

    private String serverHost;
    private int serverPort;
    private Server server;

    private AuthHandler authHandler;
    public AuthHandler getAuthHandler() {
        return authHandler;
    }

    @Listener
    public void onPreInitialization(GamePreInitializationEvent event) {
        WebAPI.instance = this;

        // Create our config directory if it doesn't exist
        if (!Files.exists(configPath)) {
            try {
                Files.createDirectories(configPath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        syncExecutor = Sponge.getScheduler().createSyncExecutor(this);
    }

    @Listener
    public void onInitialization(GameInitializationEvent event) {
        logger.info(WebAPI.NAME + " v" + WebAPI.VERSION + " is starting...");

        logger.info("Setting up jetty logger");
        Log.setLog(new JettyLogger());

        // Create permission handler
        authHandler = new AuthHandler();

        loadConfig(null);

        CommandRegistry.init();

        Reflections.log = null;
        this.reflections = new Reflections();

        logger.info(WebAPI.NAME + " ready");
    }

    private void loadConfig(Player player) {
        logger.info("Loading configuration...");

        Tuple<ConfigurationLoader, ConfigurationNode> tup = loadWithDefaults("config.conf", "defaults/config.conf");
        ConfigurationNode config = tup.getSecond();

        serverHost = config.getNode("host").getString();
        serverPort = config.getNode("port").getInt();
        CmdServlet.CMD_WAIT_TIME = config.getNode("cmdWaitTime").getInt();
        BlockServlet.MAX_BLOCK_VOLUME_SIZE = config.getNode("maxBlockVolumeSize").getInt();

        authHandler.reloadConfig();

        WebHooks.reloadConfig();

        JsonConverter.initSerializers();
        JsonConverter.loadExtraSerializers();

        CacheConfig.init();

        if (player != null) player.sendMessage(Text.builder().color(TextColors.AQUA).append(Text.of("[" + WebAPI.NAME + "] The configuration files have been reloaded!")).toText());
    }

    private void startWebServer(Player player) {
        // Start web server
        logger.info("Starting Web Server...");

        try {
            server = new Server();

            // HTTP connector
            ServerConnector http = new ServerConnector(server);
            http.setHost(serverHost);
            http.setPort(serverPort);
            http.setIdleTimeout(30000);
            server.addConnector(http);

            // Add error handler
            server.addBean(new WebAPIErrorHandler(server));

            // Collection of all handlers
            List<Handler> handlers = new LinkedList<>();

            // Asset handlers
            handlers.add(newContext("/", new AssetHandler(loadAssetString("pages/redoc.html"), "text/html; charset=utf-8")));
            String swaggerString = loadAssetString("swagger.yaml").replaceFirst("<host>", serverHost + ":" + serverPort).replaceFirst("<version>", WebAPI.VERSION);
            handlers.add(newContext("/docs", new AssetHandler(swaggerString, "application/x-yaml")));

            // Main servlet context
            ServletContextHandler servletsContext = new ServletContextHandler();
            servletsContext.setContextPath("/api");

            // Use a list to make requests first go through the auth handler and rate-limit handler
            HandlerList list = new HandlerList();
            list.setHandlers(new Handler[]{ authHandler, new RateLimitHandler(), servletsContext });
            handlers.add(list);

            servletsContext.addServlet(InfoServlet.class, "/info");

            servletsContext.addServlet(BlockServlet.class, "/block/*");
            servletsContext.addServlet(HistoryServlet.class, "/history/*");
            servletsContext.addServlet(CmdServlet.class, "/cmd/*");
            servletsContext.addServlet(WorldServlet.class, "/world/*");
            servletsContext.addServlet(PlayerServlet.class, "/player/*");
            servletsContext.addServlet(PluginServlet.class, "/plugin/*");
            servletsContext.addServlet(RecipeServlet.class, "/recipe/*");
            servletsContext.addServlet(EntityServlet.class, "/entity/*");
            servletsContext.addServlet(TileEntityServlet.class, "/tile-entity/*");

            servletsContext.addServlet(ClassServlet.class, "/class/*");

            // Add collection of handlers to server
            ContextHandlerCollection coll = new ContextHandlerCollection();
            coll.setHandlers(handlers.toArray(new Handler[handlers.size()]));
            server.setHandler(coll);

            server.start();

        } catch (Exception e) {
            e.printStackTrace();
        }

        logger.info("Web server running on " + server.getURI());

        if (player != null) {
            player.sendMessage(Text.builder().color(TextColors.AQUA).append(Text.of("[" + WebAPI.NAME + "] The web server has been restarted!")).toText());
        }
    }
    private void stopWebServer() {
        if (server != null) {
            try {
                server.stop();
                server = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private String loadAssetString(String assetPath) throws IOException {
        String res = "";
        Optional<Asset> asset = Sponge.getAssetManager().getAsset(this, assetPath);
        if (asset.isPresent()) {
            res = asset.get().readString();
        }
        return res;
    }
    private ContextHandler newContext(String path, Handler handler) {
        ContextHandler context = new ContextHandler();
        context.setContextPath(path);
        context.setHandler(handler);
        return context;
    }

    public Tuple<ConfigurationLoader, ConfigurationNode> loadWithDefaults(String path, String defaultPath) {
        try {
            Path filePath = configPath.resolve(path);
            Asset asset = Sponge.getAssetManager().getAsset(this, defaultPath).get();

            if (!Files.exists(filePath))
                asset.copyToDirectory(configPath);

            ConfigurationLoader<CommentedConfigurationNode> loader = HoconConfigurationLoader.builder().setPath(filePath).build();
            CommentedConfigurationNode config = loader.load();

            ConfigurationLoader<CommentedConfigurationNode> defLoader = HoconConfigurationLoader.builder().setURL(asset.getUrl()).build();
            CommentedConfigurationNode defConfig = defLoader.load();

            int version = config.getNode("version").getInt(0);
            int defVersion = defConfig.getNode("version").getInt(0);
            boolean newVersion = defVersion != version;

            Util.mergeConfigs(config, defConfig, newVersion);
            loader.save(config);

            if (newVersion) {
                logger.info("New configuration version '" + defVersion + "' for " + path);
                config.getNode("version").setValue(defVersion);
                loader.save(config);
            }

            return new Tuple<>(loader, config);

        } catch (IOException | NoSuchElementException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void executeCommand(String command, WebAPICommandSource source) {
        CommandManager cmdManager = Sponge.getGame().getCommandManager();
        cmdManager.process(source, command);
    }

    @Listener
    public void onServerStart(GameStartedServerEvent event) {
        DataCache.updatePlugins();
        DataCache.updateCommands();

        startWebServer(null);

        WebHooks.notifyHooks(WebHooks.WebHookType.SERVER_START, JsonConverter.toString(event));
    }
    @Listener
    public void onServerStop(GameStoppedServerEvent event) {
        WebHooks.notifyHooks(WebHooks.WebHookType.SERVER_STOP, JsonConverter.toString(event));

        stopWebServer();
    }
    @Listener
    public void onReload(GameReloadEvent event) {
        Optional<Player> p = event.getCause().first(Player.class);

        logger.info("Reloading " + WebAPI.NAME + " v" + WebAPI.VERSION + "...");

        DataCache.updatePlugins();
        DataCache.updateCommands();

        stopWebServer();

        loadConfig(p.orElse(null));

        startWebServer(p.orElse(null));

        logger.info("Reloaded " + WebAPI.NAME);
    }

    @Listener
    public void onWorldLoad(LoadWorldEvent event) {
        DataCache.addWorld(event.getTargetWorld());
    }
    @Listener
    public void onWorldUnload(UnloadWorldEvent event) {
        DataCache.removeWorld(event.getTargetWorld().getUniqueId());
    }

    @Listener(order = Order.POST)
    public void onPlayerJoin(ClientConnectionEvent.Join event) {
        DataCache.addPlayer(event.getTargetEntity());

        WebHooks.notifyHooks(WebHooks.WebHookType.PLAYER_JOIN, JsonConverter.toString(event));
    }
    @Listener(order = Order.POST)
    public void onPlayerLeave(ClientConnectionEvent.Disconnect event) {
        // Get the message first because the player is removed from cache afterwards
        String message = JsonConverter.toString(event);

        DataCache.removePlayer(event.getTargetEntity().getUniqueId());

        WebHooks.notifyHooks(WebHooks.WebHookType.PLAYER_LEAVE, message);
    }

    @Listener(order = Order.POST)
    public void onUserKick(KickPlayerEvent event) {
        WebHooks.notifyHooks(WebHooks.WebHookType.PLAYER_KICK, JsonConverter.toString(event));
    }
    @Listener(order = Order.POST)
    public void onUserBan(BanUserEvent event) {
        WebHooks.notifyHooks(WebHooks.WebHookType.PLAYER_BAN, JsonConverter.toString(event));
    }

    @Listener(order = Order.POST)
    public void onEntitySpawn(SpawnEntityEvent event) {
        for (Entity entity : event.getEntities()) {
            DataCache.addEntity(entity);
        }
    }
    @Listener(order = Order.POST)
    public void onEntityDespawn(DestructEntityEvent event) {
        DataCache.removeEntity(event.getTargetEntity().getUniqueId());

        Entity ent = event.getTargetEntity();
        if (ent instanceof Player) {
            WebHooks.notifyHooks(WebHooks.WebHookType.PLAYER_DEATH, JsonConverter.toString(event));
        }
    }

    @Listener(order = Order.POST)
    public void onPlayerChat(MessageChannelEvent.Chat event, @First Player player) {
        CachedChatMessage msg = DataCache.addChatMessage(player, event);
        WebHooks.notifyHooks(WebHooks.WebHookType.CHAT, JsonConverter.toString(msg));
    }

    @Listener(order = Order.POST)
    public void onInteractInventory(InteractInventoryEvent.Open event) {
        WebHooks.notifyHooks(WebHooks.WebHookType.INVENTORY_OPEN, JsonConverter.toString(event));
    }
    @Listener(order = Order.POST)
    public void onInteractInventory(InteractInventoryEvent.Close event) {
        WebHooks.notifyHooks(WebHooks.WebHookType.INVENTORY_CLOSE, JsonConverter.toString(event));
    }

    @Listener(order = Order.POST)
    public void onPlayerAchievement(GrantAchievementEvent.TargetPlayer event) {
        Player player = event.getTargetEntity();

        // Check if we already have the achievement
        if (player.getAchievementData().achievements().get().stream().anyMatch(a -> a.getId().equals(event.getAchievement().getId())))
            return;

        WebHooks.notifyHooks(WebHooks.WebHookType.ACHIEVEMENT, JsonConverter.toString(event));
    }

    @Listener(order = Order.POST)
    public void onCommand(SendCommandEvent event) {
        CachedCommandCall call = DataCache.addCommandCall(event);

        WebHooks.notifyHooks(WebHooks.WebHookType.COMMAND, JsonConverter.toString(call));
    }
}
