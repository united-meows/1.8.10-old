package net.minecraft.server;

import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.security.KeyPair;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandResultStats;
import net.minecraft.command.ICommandManager;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.ServerCommandManager;
import net.minecraft.crash.CrashReport;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.NetworkSystem;
import net.minecraft.network.ServerStatusResponse;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.profiler.IPlayerUsage;
import net.minecraft.profiler.PlayerUsageSnooper;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.management.PlayerProfileCache;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.util.IThreadListener;
import net.minecraft.util.ITickable;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ReportedException;
import net.minecraft.util.Util;
import net.minecraft.util.Vec3;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.World;
import net.minecraft.world.WorldManager;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldServerMulti;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.storage.AnvilSaveConverter;
import net.minecraft.world.demo.DemoWorldServer;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;

public abstract class MinecraftServer implements Runnable, ICommandSender, IThreadListener, IPlayerUsage {
	private static final Logger logger = LogManager.getLogger();
	public static final File USER_CACHE_FILE = new File("usercache.json");
	/** Instance of Minecraft Server. */
	private static MinecraftServer mcServer;
	private final ISaveFormat anvilConverterForAnvilFile;
	/** The PlayerUsageSnooper instance. */
	private final PlayerUsageSnooper usageSnooper = new PlayerUsageSnooper("server", this, getCurrentTimeMillis());
	private final File anvilFile;
	private final List<ITickable> playersOnline = Lists.<ITickable>newArrayList();
	protected final ICommandManager commandManager;
	public final Profiler theProfiler = new Profiler();
	private final NetworkSystem networkSystem;
	private final ServerStatusResponse statusResponse = new ServerStatusResponse();
	private final Random random = new Random();
	/** The server's port. */
	private final int serverPort = -1;
	/** The server world instances. */
	public WorldServer[] worldServers;
	/** The ServerConfigurationManager instance. */
	private ServerConfigurationManager serverConfigManager;
	/**
	 * Indicates whether the server is running or not. Set to false to initiate a shutdown.
	 */
	private boolean serverRunning = true;
	/** Indicates to other classes that the server is safely stopped. */
	private boolean serverStopped;
	/** Incremented every tick. */
	private int tickCounter;
	protected final Proxy serverProxy;
	/**
	 * The task the server is currently working on(and will output on outputPercentRemaining).
	 */
	public String currentTask;
	/** The percentage of the current task finished so far. */
	public int percentDone;
	/** True if the server is in online mode. */
	private boolean onlineMode;
	/** True if the server has animals turned on. */
	private boolean canSpawnAnimals;
	private boolean canSpawnNPCs;
	/** Indicates whether PvP is active on the server or not. */
	private boolean pvpEnabled;
	/** Determines if flight is allowed or not. */
	private boolean allowFlight;
	/** The server MOTD string. */
	private String motd;
	/** Maximum build height. */
	private int buildLimit;
	private int maxPlayerIdleMinutes = 0;
	public final long[] tickTimeArray = new long[100];
	/** Stats are [dimension][tick%100] system.nanoTime is stored. */
	public long[][] timeOfLastDimensionTick;
	private KeyPair serverKeyPair;
	/** Username of the server owner (for integrated servers) */
	private String serverOwner;
	private String folderName;
	private String worldName;
	private boolean isDemo;
	private boolean enableBonusChest;
	/**
	 * If true, there is no need to save chunks or stop the server, because that is already being done.
	 */
	private boolean worldIsBeingDeleted;
	/** The texture pack for the server */
	private String resourcePackUrl = "";
	private String resourcePackHash = "";
	private boolean serverIsRunning;
	/**
	 * Set when warned for "Can't keep up", which triggers again after 15 seconds.
	 */
	private long timeOfLastWarning;
	private String userMessage;
	private boolean startProfiling;
	private boolean isGamemodeForced;
	private final YggdrasilAuthenticationService authService;
	private final MinecraftSessionService sessionService;
	private long nanoTimeSinceStatusRefresh = 0L;
	private final GameProfileRepository profileRepo;
	private final PlayerProfileCache profileCache;
	protected final Queue<FutureTask<?>> futureTaskQueue = Queues.<FutureTask<?>>newArrayDeque();
	private Thread serverThread;
	private long currentTime = getCurrentTimeMillis();

	public MinecraftServer(final Proxy proxy, final File workDir)
	{
		this.serverProxy = proxy;
		mcServer = this;
		this.anvilFile = null;
		this.networkSystem = null;
		this.profileCache = new PlayerProfileCache(this, workDir);
		this.commandManager = null;
		this.anvilConverterForAnvilFile = null;
		this.authService = new YggdrasilAuthenticationService(proxy, UUID.randomUUID().toString());
		this.sessionService = this.authService.createMinecraftSessionService();
		this.profileRepo = this.authService.createProfileRepository();
	}

	public MinecraftServer(final File workDir, final Proxy proxy, final File profileCacheDir)
	{
		this.serverProxy = proxy;
		mcServer = this;
		this.anvilFile = workDir;
		this.networkSystem = new NetworkSystem(this);
		this.profileCache = new PlayerProfileCache(this, profileCacheDir);
		this.commandManager = this.createNewCommandManager();
		this.anvilConverterForAnvilFile = new AnvilSaveConverter(workDir);
		this.authService = new YggdrasilAuthenticationService(proxy, UUID.randomUUID().toString());
		this.sessionService = this.authService.createMinecraftSessionService();
		this.profileRepo = this.authService.createProfileRepository();
	}

	protected ServerCommandManager createNewCommandManager() { return new ServerCommandManager(); }

	/**
	 * Initialises the server and starts it.
	 */
	protected abstract boolean startServer() throws IOException;

	protected void convertMapIfNeeded(final String worldNameIn) {
		if (this.getActiveAnvilConverter().isOldMapFormat(worldNameIn)) {
			logger.info("Converting map!");
			this.setUserMessage("menu.convertingLevel");
			this.getActiveAnvilConverter().convertMapFormat(worldNameIn, new IProgressUpdate() {
				private long startTime = System.currentTimeMillis();

				@Override
				public void displaySavingString(final String message) {}

				@Override
				public void resetProgressAndMessage(final String message) {}

				@Override
				public void setLoadingProgress(final int progress) {
					if (System.currentTimeMillis() - this.startTime >= 1000L) {
						this.startTime = System.currentTimeMillis();
						MinecraftServer.logger.info("Converting... " + progress + "%");
					}
				}

				@Override
				public void setDoneWorking() {}

				@Override
				public void displayLoadingString(final String message) {}
			});
		}
	}

	/**
	 * Typically "menu.convertingLevel", "menu.loadingLevel" or others.
	 */
	protected synchronized void setUserMessage(final String message) { this.userMessage = message; }

	public synchronized String getUserMessage() { return this.userMessage; }

	protected void loadAllWorlds(final String saveName, final String worldNameIn, final long seed, final WorldType type, final String worldNameIn2) {
		this.convertMapIfNeeded(saveName);
		this.setUserMessage("menu.loadingLevel");
		this.worldServers = new WorldServer[3];
		this.timeOfLastDimensionTick = new long[this.worldServers.length][100];
		final ISaveHandler isavehandler = this.anvilConverterForAnvilFile.getSaveLoader(saveName, true);
		this.setResourcePackFromWorld(this.getFolderName(), isavehandler);
		WorldInfo worldinfo = isavehandler.loadWorldInfo();
		WorldSettings worldsettings;
		if (worldinfo == null) {
			if (this.isDemo()) worldsettings = DemoWorldServer.demoWorldSettings;
			else {
				worldsettings = new WorldSettings(seed, this.getGameType(), this.canStructuresSpawn(), this.isHardcore(), type);
				worldsettings.setWorldName(worldNameIn2);
				if (this.enableBonusChest) worldsettings.enableBonusChest();
			}
			worldinfo = new WorldInfo(worldsettings, worldNameIn);
		} else {
			worldinfo.setWorldName(worldNameIn);
			worldsettings = new WorldSettings(worldinfo);
		}
		for (int i = 0; i < this.worldServers.length; ++i) {
			int j = 0;
			if (i == 1) j = -1;
			if (i == 2) j = 1;
			if (i == 0) {
				if (this.isDemo()) this.worldServers[i] = (WorldServer) (new DemoWorldServer(this, isavehandler, worldinfo, j, this.theProfiler)).init();
				else this.worldServers[i] = (WorldServer) (new WorldServer(this, isavehandler, worldinfo, j, this.theProfiler)).init();
				this.worldServers[i].initialize(worldsettings);
			} else this.worldServers[i] = (WorldServer) (new WorldServerMulti(this, isavehandler, j, this.worldServers[0], this.theProfiler)).init();
			this.worldServers[i].addWorldAccess(new WorldManager(this, this.worldServers[i]));
			if (!this.isSinglePlayer()) this.worldServers[i].getWorldInfo().setGameType(this.getGameType());
		}
		this.serverConfigManager.setPlayerManager(this.worldServers);
		this.setDifficultyForAllWorlds(this.getDifficulty());
		this.initialWorldChunkLoad();
	}

	protected void initialWorldChunkLoad() {
		/**
		 * @reason load worlds %50000 faster
		 */
		final boolean fix = true;
		if (fix) return;
		final int i = 16;
		final int j = 4;
		final int k = 192;
		final int l = 625;
		int i1 = 0;
		this.setUserMessage("menu.generatingTerrain");
		final int j1 = 0;
		logger.info("Preparing start region for level " + j1);
		final WorldServer worldserver = this.worldServers[j1];
		final BlockPos blockpos = worldserver.getSpawnPoint();
		long k1 = getCurrentTimeMillis();
		for (int l1 = -192; l1 <= 192 && this.isServerRunning(); l1 += 16) for (int i2 = -192; i2 <= 192 && this.isServerRunning(); i2 += 16) {
			final long j2 = getCurrentTimeMillis();
			if (j2 - k1 > 1000L) {
				this.outputPercentRemaining("Preparing spawn area", i1 * 100 / 625);
				k1 = j2;
			}
			++i1;
			worldserver.theChunkProviderServer.loadChunk(blockpos.getX() + l1 >> 4, blockpos.getZ() + i2 >> 4);
		}
		this.clearCurrentTask();
	}

	protected void setResourcePackFromWorld(final String worldNameIn, final ISaveHandler saveHandlerIn) {
		final File file1 = new File(saveHandlerIn.getWorldDirectory(), "resources.zip");
		if (file1.isFile()) this.setResourcePack("level://" + worldNameIn + "/" + file1.getName(), "");
	}

	public abstract boolean canStructuresSpawn();

	public abstract WorldSettings.GameType getGameType();

	/**
	 * Get the server's difficulty
	 */
	public abstract EnumDifficulty getDifficulty();

	/**
	 * Defaults to false.
	 */
	public abstract boolean isHardcore();

	public abstract int getOpPermissionLevel();

	/**
	 * Get if RCON command events should be broadcast to ops
	 */
	public abstract boolean shouldBroadcastRconToOps();

	/**
	 * Get if console command events should be broadcast to ops
	 */
	public abstract boolean shouldBroadcastConsoleToOps();

	/**
	 * Used to display a percent remaining given text and the percentage.
	 */
	protected void outputPercentRemaining(final String message, final int percent) {
		this.currentTask = message;
		this.percentDone = percent;
		logger.info(message + ": " + percent + "%");
	}

	/**
	 * Set current task to null and set its percentage to 0.
	 */
	protected void clearCurrentTask() {
		this.currentTask = null;
		this.percentDone = 0;
	}

	/**
	 * par1 indicates if a log message should be output.
	 */
	protected void saveAllWorlds(final boolean dontLog) {
		if (!this.worldIsBeingDeleted) for (final WorldServer worldserver : this.worldServers) if (worldserver != null) {
			if (!dontLog) logger.info("Saving chunks for level \'" + worldserver.getWorldInfo().getWorldName() + "\'/" + worldserver.provider.getDimensionName());
			try {
				worldserver.saveAllChunks(true, (IProgressUpdate) null);
			} catch (final MinecraftException minecraftexception) {
				logger.warn(minecraftexception.getMessage());
			}
		}
	}

	/**
	 * Saves all necessary data as preparation for stopping the server.
	 */
	public void stopServer() {
		if (!this.worldIsBeingDeleted) {
			logger.info("Stopping server");
			if (this.getNetworkSystem() != null) this.getNetworkSystem().terminateEndpoints();
			if (this.serverConfigManager != null) {
				logger.info("Saving players");
				this.serverConfigManager.saveAllPlayerData();
				this.serverConfigManager.removeAllPlayers();
			}
			if (this.worldServers != null) {
				logger.info("Saving worlds");
				this.saveAllWorlds(false);
				for (final WorldServer worldserver : this.worldServers) worldserver.flush();
			}
			if (this.usageSnooper.isSnooperRunning()) this.usageSnooper.stopSnooper();
		}
	}

	public boolean isServerRunning() { return this.serverRunning; }

	/**
	 * Sets the serverRunning variable to false, in order to get the server to shut down.
	 */
	public void initiateShutdown() { this.serverRunning = false; }

	protected void setInstance() { mcServer = this; }

	@Override
	public void run() {
		try {
			if (this.startServer()) {
				this.currentTime = getCurrentTimeMillis();
				long i = 0L;
				this.statusResponse.setServerDescription(new ChatComponentText(this.motd));
				this.statusResponse.setProtocolVersionInfo(new ServerStatusResponse.MinecraftProtocolVersionIdentifier("1.8.9", 47));
				this.addFaviconToStatusResponse(this.statusResponse);
				while (this.serverRunning) {
					final long k = getCurrentTimeMillis();
					long j = k - this.currentTime;
					if (j > 2000L && this.currentTime - this.timeOfLastWarning >= 15000L) {
						logger.warn("Can\'t keep up! Did the system time change, or is the server overloaded? Running {}ms behind, skipping {} tick(s)", Long.valueOf(j), Long.valueOf(j / 50L));
						j = 2000L;
						this.timeOfLastWarning = this.currentTime;
					}
					if (j < 0L) {
						logger.warn("Time ran backwards! Did the system time change?");
						j = 0L;
					}
					i += j;
					this.currentTime = k;
					if (this.worldServers[0].areAllPlayersAsleep()) {
						this.tick();
						i = 0L;
					} else while (i > 50L) { i -= 50L; this.tick(); }
					Thread.sleep(Math.max(1L, 50L - i));
					this.serverIsRunning = true;
				}
			} else this.finalTick((CrashReport) null);
		} catch (final Throwable throwable1) {
			logger.error("Encountered an unexpected exception", throwable1);
			CrashReport crashreport = null;
			if (throwable1 instanceof ReportedException) crashreport = this.addServerInfoToCrashReport(((ReportedException) throwable1).getCrashReport());
			else crashreport = this.addServerInfoToCrashReport(new CrashReport("Exception in server tick loop", throwable1));
			final File file1 = new File(new File(this.getDataDirectory(), "crash-reports"), "crash-" + (new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss")).format(new Date()) + "-server.txt");
			if (crashreport.saveToFile(file1)) logger.error("This crash report has been saved to: " + file1.getAbsolutePath());
			else logger.error("We were unable to save this crash report to disk.");
			this.finalTick(crashreport);
		} finally {
			try {
				this.serverStopped = true;
				this.stopServer();
			} catch (final Throwable throwable) {
				logger.error("Exception stopping the server", throwable);
			} finally {
				this.systemExitNow();
			}
		}
	}

	private void addFaviconToStatusResponse(final ServerStatusResponse response) {
		final File file1 = this.getFile("server-icon.png");
		if (file1.isFile()) {
			final ByteBuf bytebuf = Unpooled.buffer();
			try {
				final BufferedImage bufferedimage = ImageIO.read(file1);
				Validate.validState(bufferedimage.getWidth() == 64, "Must be 64 pixels wide");
				Validate.validState(bufferedimage.getHeight() == 64, "Must be 64 pixels high");
				ImageIO.write(bufferedimage, "PNG", (new ByteBufOutputStream(bytebuf)));
				final ByteBuf bytebuf1 = Base64.encode(bytebuf);
				response.setFavicon("data:image/png;base64," + bytebuf1.toString(Charsets.UTF_8));
			} catch (final Exception exception) {
				logger.error("Couldn\'t load server icon", exception);
			} finally {
				bytebuf.release();
			}
		}
	}

	public File getDataDirectory() { return new File("."); }

	/**
	 * Called on exit from the main run() loop.
	 */
	protected void finalTick(final CrashReport report) {}

	/**
	 * Directly calls System.exit(0), instantly killing the program.
	 */
	protected void systemExitNow() {}

	/**
	 * Main function called by run() every loop.
	 */
	public void tick() {
		final long i = System.nanoTime();
		++this.tickCounter;
		if (this.startProfiling) {
			this.startProfiling = false;
			this.theProfiler.profilingEnabled = true;
			this.theProfiler.clearProfiling();
		}
		this.theProfiler.startSection("root");
		this.updateTimeLightAndEntities();
		if (i - this.nanoTimeSinceStatusRefresh >= 5000000000L) {
			this.nanoTimeSinceStatusRefresh = i;
			this.statusResponse.setPlayerCountData(new ServerStatusResponse.PlayerCountData(this.getMaxPlayers(), this.getCurrentPlayerCount()));
			final GameProfile[] agameprofile = new GameProfile[Math.min(this.getCurrentPlayerCount(), 12)];
			final int j = MathHelper.getRandomIntegerInRange(this.random, 0, this.getCurrentPlayerCount() - agameprofile.length);
			for (int k = 0; k < agameprofile.length; ++k) agameprofile[k] = this.serverConfigManager.getPlayerList().get(j + k).getGameProfile();
			Collections.shuffle(Arrays.asList(agameprofile));
			this.statusResponse.getPlayerCountData().setPlayers(agameprofile);
		}
		if (this.tickCounter % 900 == 0) {
			this.theProfiler.startSection("save");
			this.serverConfigManager.saveAllPlayerData();
			this.saveAllWorlds(true);
			this.theProfiler.endSection();
		}
		this.theProfiler.startSection("tallying");
		this.tickTimeArray[this.tickCounter % 100] = System.nanoTime() - i;
		this.theProfiler.endSection();
		this.theProfiler.startSection("snooper");
		if (!this.usageSnooper.isSnooperRunning() && this.tickCounter > 100) this.usageSnooper.startSnooper();
		if (this.tickCounter % 6000 == 0) this.usageSnooper.addMemoryStatsToSnooper();
		this.theProfiler.endSection();
		this.theProfiler.endSection();
	}

	public void updateTimeLightAndEntities() {
		this.theProfiler.startSection("jobs");
		synchronized (this.futureTaskQueue) {
			while (!this.futureTaskQueue.isEmpty()) Util.runTask((FutureTask) this.futureTaskQueue.poll(), logger);
		}
		this.theProfiler.endStartSection("levels");
		for (int j = 0; j < this.worldServers.length; ++j) {
			final long i = System.nanoTime();
			if (j == 0 || this.getAllowNether()) {
				final WorldServer worldserver = this.worldServers[j];
				this.theProfiler.startSection(worldserver.getWorldInfo().getWorldName());
				if (this.tickCounter % 20 == 0) {
					this.theProfiler.startSection("timeSync");
					this.serverConfigManager.sendPacketToAllPlayersInDimension(new S03PacketTimeUpdate(worldserver.getTotalWorldTime(), worldserver.getWorldTime(), worldserver.getGameRules().getBoolean("doDaylightCycle")),
							worldserver.provider.getDimensionId());
					this.theProfiler.endSection();
				}
				this.theProfiler.startSection("tick");
				try {
					worldserver.tick();
				} catch (final Throwable throwable1) {
					final CrashReport crashreport = CrashReport.makeCrashReport(throwable1, "Exception ticking world");
					worldserver.addWorldInfoToCrashReport(crashreport);
					throw new ReportedException(crashreport);
				}
				try {
					worldserver.updateEntities();
				} catch (final Throwable throwable) {
					final CrashReport crashreport1 = CrashReport.makeCrashReport(throwable, "Exception ticking world entities");
					worldserver.addWorldInfoToCrashReport(crashreport1);
					throw new ReportedException(crashreport1);
				}
				this.theProfiler.endSection();
				this.theProfiler.startSection("tracker");
				worldserver.getEntityTracker().updateTrackedEntities();
				this.theProfiler.endSection();
				this.theProfiler.endSection();
			}
			this.timeOfLastDimensionTick[j][this.tickCounter % 100] = System.nanoTime() - i;
		}
		this.theProfiler.endStartSection("connection");
		this.getNetworkSystem().networkTick();
		this.theProfiler.endStartSection("players");
		this.serverConfigManager.onTick();
		this.theProfiler.endStartSection("tickables");
		for (final ITickable element : this.playersOnline) element.update();
		this.theProfiler.endSection();
	}

	public boolean getAllowNether() { return true; }

	public void startServerThread() {
		this.serverThread = new Thread(this, "Server thread");
		this.serverThread.start();
	}

	/**
	 * Returns a File object from the specified string.
	 */
	public File getFile(final String fileName) { return new File(this.getDataDirectory(), fileName); }

	/**
	 * Logs the message with a level of WARN.
	 */
	public void logWarning(final String msg) { logger.warn(msg); }

	/**
	 * Gets the worldServer by the given dimension.
	 */
	public WorldServer worldServerForDimension(final int dimension) { return dimension == -1 ? this.worldServers[1] : (dimension == 1 ? this.worldServers[2] : this.worldServers[0]); }

	/**
	 * Returns the server's Minecraft version as string.
	 */
	public String getMinecraftVersion() { return "1.8.9"; }

	/**
	 * Returns the number of players currently on the server.
	 */
	public int getCurrentPlayerCount() { return this.serverConfigManager.getCurrentPlayerCount(); }

	/**
	 * Returns the maximum number of players allowed on the server.
	 */
	public int getMaxPlayers() { return this.serverConfigManager.getMaxPlayers(); }

	/**
	 * Returns an array of the usernames of all the connected players.
	 */
	public String[] getAllUsernames() { return this.serverConfigManager.getAllUsernames(); }

	/**
	 * Returns an array of the GameProfiles of all the connected players
	 */
	public GameProfile[] getGameProfiles() { return this.serverConfigManager.getAllProfiles(); }

	public String getServerModName() { return "vanilla"; }

	/**
	 * Adds the server info, including from theWorldServer, to the crash report.
	 */
	public CrashReport addServerInfoToCrashReport(final CrashReport report) {
		report.getCategory().addCrashSectionCallable("Profiler Position", () -> MinecraftServer.this.theProfiler.profilingEnabled ? MinecraftServer.this.theProfiler.getNameOfLastSection() : "N/A (disabled)");
		if (this.serverConfigManager != null) report.getCategory().addCrashSectionCallable("Player Count",
				() -> MinecraftServer.this.serverConfigManager.getCurrentPlayerCount() + " / " + MinecraftServer.this.serverConfigManager.getMaxPlayers() + "; " + MinecraftServer.this.serverConfigManager.getPlayerList());
		return report;
	}

	public List<String> getTabCompletions(final ICommandSender sender, String input, final BlockPos pos) {
		final List<String> list = Lists.<String>newArrayList();
		if (input.startsWith("/")) {
			input = input.substring(1);
			final boolean flag = !input.contains(" ");
			final List<String> list1 = this.commandManager.getTabCompletionOptions(sender, input, pos);
			if (list1 != null) for (final String s2 : list1) if (flag) list.add("/" + s2);
			else list.add(s2);
			return list;
		} else {
			final String[] astring = input.split(" ", -1);
			final String s = astring[astring.length - 1];
			for (final String s1 : this.serverConfigManager.getAllUsernames()) if (CommandBase.doesStringStartWith(s, s1)) list.add(s1);
			return list;
		}
	}

	/**
	 * Gets mcServer.
	 */
	public static MinecraftServer getServer() { return mcServer; }

	public boolean isAnvilFileSet() { return this.anvilFile != null; }

	/**
	 * Get the name of this object. For players this returns their username
	 */
	@Override
	public String getName() { return "Server"; }

	/**
	 * Send a chat message to the CommandSender
	 */
	@Override
	public void addChatMessage(final IChatComponent component) { logger.info(component.getUnformattedText()); }

	/**
	 * Returns {@code true} if the CommandSender is allowed to execute the command, {@code false} if not
	 */
	@Override
	public boolean canCommandSenderUseCommand(final int permLevel, final String commandName) { return true; }

	public ICommandManager getCommandManager() { return this.commandManager; }

	/**
	 * Gets KeyPair instanced in MinecraftServer.
	 */
	public KeyPair getKeyPair() { return this.serverKeyPair; }

	/**
	 * Returns the username of the server owner (for integrated servers)
	 */
	public String getServerOwner() { return this.serverOwner; }

	/**
	 * Sets the username of the owner of this server (in the case of an integrated server)
	 */
	public void setServerOwner(final String owner) { this.serverOwner = owner; }

	public boolean isSinglePlayer() { return this.serverOwner != null; }

	public String getFolderName() { return this.folderName; }

	public void setFolderName(final String name) { this.folderName = name; }

	public void setWorldName(final String p_71246_1_) { this.worldName = p_71246_1_; }

	public String getWorldName() { return this.worldName; }

	public void setKeyPair(final KeyPair keyPair) { this.serverKeyPair = keyPair; }

	public void setDifficultyForAllWorlds(final EnumDifficulty difficulty) {
		for (final WorldServer world : this.worldServers) if (world != null) if (world.getWorldInfo().isHardcoreModeEnabled()) {
			world.getWorldInfo().setDifficulty(EnumDifficulty.HARD);
			world.setAllowedSpawnTypes(true, true);
		} else if (this.isSinglePlayer()) {
			world.getWorldInfo().setDifficulty(difficulty);
			world.setAllowedSpawnTypes(world.getDifficulty() != EnumDifficulty.PEACEFUL, true);
		} else {
			world.getWorldInfo().setDifficulty(difficulty);
			world.setAllowedSpawnTypes(this.allowSpawnMonsters(), this.canSpawnAnimals);
		}
	}

	protected boolean allowSpawnMonsters() { return true; }

	/**
	 * Gets whether this is a demo or not.
	 */
	public boolean isDemo() { return this.isDemo; }

	/**
	 * Sets whether this is a demo or not.
	 */
	public void setDemo(final boolean demo) { this.isDemo = demo; }

	public void canCreateBonusChest(final boolean enable) { this.enableBonusChest = enable; }

	public ISaveFormat getActiveAnvilConverter() { return this.anvilConverterForAnvilFile; }

	/**
	 * WARNING : directly calls
	 * getActiveAnvilConverter().deleteWorldDirectory(theWorldServer[0].getSaveHandler().getWorldDirectoryName());
	 */
	public void deleteWorldAndStopServer() {
		this.worldIsBeingDeleted = true;
		this.getActiveAnvilConverter().flushCache();
		for (final WorldServer worldserver : this.worldServers) if (worldserver != null) worldserver.flush();
		this.getActiveAnvilConverter().deleteWorldDirectory(this.worldServers[0].getSaveHandler().getWorldDirectoryName());
		this.initiateShutdown();
	}

	public String getResourcePackUrl() { return this.resourcePackUrl; }

	public String getResourcePackHash() { return this.resourcePackHash; }

	public void setResourcePack(final String url, final String hash) {
		this.resourcePackUrl = url;
		this.resourcePackHash = hash;
	}

	@Override
	public void addServerStatsToSnooper(final PlayerUsageSnooper playerSnooper) {
		playerSnooper.addClientStat("whitelist_enabled", Boolean.valueOf(false));
		playerSnooper.addClientStat("whitelist_count", Integer.valueOf(0));
		if (this.serverConfigManager != null) {
			playerSnooper.addClientStat("players_current", Integer.valueOf(this.getCurrentPlayerCount()));
			playerSnooper.addClientStat("players_max", Integer.valueOf(this.getMaxPlayers()));
			playerSnooper.addClientStat("players_seen", Integer.valueOf(this.serverConfigManager.getAvailablePlayerDat().length));
		}
		playerSnooper.addClientStat("uses_auth", Boolean.valueOf(this.onlineMode));
		playerSnooper.addClientStat("gui_state", this.getGuiEnabled() ? "enabled" : "disabled");
		playerSnooper.addClientStat("run_time", Long.valueOf((getCurrentTimeMillis() - playerSnooper.getMinecraftStartTimeMillis()) / 60L * 1000L));
		playerSnooper.addClientStat("avg_tick_ms", Integer.valueOf((int) (MathHelper.average(this.tickTimeArray) * 1.0E-6D)));
		int i = 0;
		if (this.worldServers != null) for (final WorldServer worldserver : this.worldServers) if (worldserver != null) {
			final WorldInfo worldinfo = worldserver.getWorldInfo();
			playerSnooper.addClientStat("world[" + i + "][dimension]", Integer.valueOf(worldserver.provider.getDimensionId()));
			playerSnooper.addClientStat("world[" + i + "][mode]", worldinfo.getGameType());
			playerSnooper.addClientStat("world[" + i + "][difficulty]", worldserver.getDifficulty());
			playerSnooper.addClientStat("world[" + i + "][hardcore]", Boolean.valueOf(worldinfo.isHardcoreModeEnabled()));
			playerSnooper.addClientStat("world[" + i + "][generator_name]", worldinfo.getTerrainType().getWorldTypeName());
			playerSnooper.addClientStat("world[" + i + "][generator_version]", Integer.valueOf(worldinfo.getTerrainType().getGeneratorVersion()));
			playerSnooper.addClientStat("world[" + i + "][height]", Integer.valueOf(this.buildLimit));
			playerSnooper.addClientStat("world[" + i + "][chunks_loaded]", Integer.valueOf(worldserver.getChunkProvider().getLoadedChunkCount()));
			++i;
		}
		playerSnooper.addClientStat("worlds", Integer.valueOf(i));
	}

	@Override
	public void addServerTypeToSnooper(final PlayerUsageSnooper playerSnooper) {
		playerSnooper.addStatToSnooper("singleplayer", Boolean.valueOf(this.isSinglePlayer()));
		playerSnooper.addStatToSnooper("server_brand", this.getServerModName());
		playerSnooper.addStatToSnooper("gui_supported", GraphicsEnvironment.isHeadless() ? "headless" : "supported");
		playerSnooper.addStatToSnooper("dedicated", Boolean.valueOf(this.isDedicatedServer()));
	}

	/**
	 * Returns whether snooping is enabled or not.
	 */
	@Override
	public boolean isSnooperEnabled() { return true; }

	public abstract boolean isDedicatedServer();

	public boolean isServerInOnlineMode() { return this.onlineMode; }

	public void setOnlineMode(final boolean online) { this.onlineMode = online; }

	public boolean getCanSpawnAnimals() { return this.canSpawnAnimals; }

	public void setCanSpawnAnimals(final boolean spawnAnimals) { this.canSpawnAnimals = spawnAnimals; }

	public boolean getCanSpawnNPCs() { return this.canSpawnNPCs; }

	/**
	 * Get if native transport should be used. Native transport means linux server performance
	 * improvements and optimized packet sending/receiving on linux
	 */
	public abstract boolean shouldUseNativeTransport();

	public void setCanSpawnNPCs(final boolean spawnNpcs) { this.canSpawnNPCs = spawnNpcs; }

	public boolean isPVPEnabled() { return this.pvpEnabled; }

	public void setAllowPvp(final boolean allowPvp) { this.pvpEnabled = allowPvp; }

	public boolean isFlightAllowed() { return this.allowFlight; }

	public void setAllowFlight(final boolean allow) { this.allowFlight = allow; }

	/**
	 * Return whether command blocks are enabled.
	 */
	public abstract boolean isCommandBlockEnabled();

	public String getMOTD() { return this.motd; }

	public void setMOTD(final String motdIn) { this.motd = motdIn; }

	public int getBuildLimit() { return this.buildLimit; }

	public void setBuildLimit(final int maxBuildHeight) { this.buildLimit = maxBuildHeight; }

	public boolean isServerStopped() { return this.serverStopped; }

	public ServerConfigurationManager getConfigurationManager() { return this.serverConfigManager; }

	public void setConfigManager(final ServerConfigurationManager configManager) { this.serverConfigManager = configManager; }

	/**
	 * Sets the game type for all worlds.
	 */
	public void setGameType(final WorldSettings.GameType gameMode) { for (int i = 0; i < this.worldServers.length; ++i) getServer().worldServers[i].getWorldInfo().setGameType(gameMode); }

	public NetworkSystem getNetworkSystem() { return this.networkSystem; }

	public boolean serverIsInRunLoop() { return this.serverIsRunning; }

	public boolean getGuiEnabled() { return false; }

	/**
	 * On dedicated does nothing. On integrated, sets commandsAllowedForAll, gameType and allows
	 * external connections.
	 */
	public abstract String shareToLAN(WorldSettings.GameType type, boolean allowCheats);

	public int getTickCounter() { return this.tickCounter; }

	public void enableProfiling() { this.startProfiling = true; }

	public PlayerUsageSnooper getPlayerUsageSnooper() { return this.usageSnooper; }

	/**
	 * Get the position in the world. <b>{@code null} is not allowed!</b> If you are not an entity in
	 * the world, return the coordinates 0, 0, 0
	 */
	@Override
	public BlockPos getPosition() { return BlockPos.ORIGIN; }

	/**
	 * Get the position vector. <b>{@code null} is not allowed!</b> If you are not an entity in the
	 * world, return 0.0D, 0.0D, 0.0D
	 */
	@Override
	public Vec3 getPositionVector() { return new Vec3(0.0D, 0.0D, 0.0D); }

	/**
	 * Get the world, if available. <b>{@code null} is not allowed!</b> If you are not an entity in the
	 * world, return the overworld
	 */
	@Override
	public World getEntityWorld() { return this.worldServers[0]; }

	/**
	 * Returns the entity associated with the command sender. MAY BE NULL!
	 */
	@Override
	public Entity getCommandSenderEntity() { return null; }

	/**
	 * Return the spawn protection area's size.
	 */
	public int getSpawnProtectionSize() { return 16; }

	public boolean isBlockProtected(final World worldIn, final BlockPos pos, final EntityPlayer playerIn) { return false; }

	public boolean getForceGamemode() { return this.isGamemodeForced; }

	public Proxy getServerProxy() { return this.serverProxy; }

	public static long getCurrentTimeMillis() { return System.currentTimeMillis(); }

	public int getMaxPlayerIdleMinutes() { return this.maxPlayerIdleMinutes; }

	public void setPlayerIdleTimeout(final int idleTimeout) { this.maxPlayerIdleMinutes = idleTimeout; }

	/**
	 * Get the formatted ChatComponent that will be used for the sender's username in chat
	 */
	@Override
	public IChatComponent getDisplayName() { return new ChatComponentText(this.getName()); }

	public boolean isAnnouncingPlayerAchievements() { return true; }

	public MinecraftSessionService getMinecraftSessionService() { return this.sessionService; }

	public GameProfileRepository getGameProfileRepository() { return this.profileRepo; }

	public PlayerProfileCache getPlayerProfileCache() { return this.profileCache; }

	public ServerStatusResponse getServerStatusResponse() { return this.statusResponse; }

	public void refreshStatusNextTick() { this.nanoTimeSinceStatusRefresh = 0L; }

	public Entity getEntityFromUuid(final UUID uuid) {
		for (final WorldServer worldserver : this.worldServers) if (worldserver != null) {
			final Entity entity = worldserver.getEntityFromUuid(uuid);
			if (entity != null) return entity;
		}
		return null;
	}

	/**
	 * Returns true if the command sender should be sent feedback about executed commands
	 */
	@Override
	public boolean sendCommandFeedback() { return getServer().worldServers[0].getGameRules().getBoolean("sendCommandFeedback"); }

	@Override
	public void setCommandStat(final CommandResultStats.Type type, final int amount) {}

	public int getMaxWorldSize() { return 29999984; }

	public <V> ListenableFuture<V> callFromMainThread(final Callable<V> callable) {
		Validate.notNull(callable);
		if (!this.isCallingFromMinecraftThread() && !this.isServerStopped()) {
			final ListenableFutureTask<V> listenablefuturetask = ListenableFutureTask.<V>create(callable);
			synchronized (this.futureTaskQueue) {
				this.futureTaskQueue.add(listenablefuturetask);
				return listenablefuturetask;
			}
		} else try {
			return Futures.<V>immediateFuture(callable.call());
		} catch (final Exception exception) {
			return Futures.immediateFailedCheckedFuture(exception);
		}
	}

	@Override
	public ListenableFuture<Object> addScheduledTask(final Runnable runnableToSchedule) {
		Validate.notNull(runnableToSchedule);
		return this.<Object>callFromMainThread(Executors.callable(runnableToSchedule));
	}

	@Override
	public boolean isCallingFromMinecraftThread() { return Thread.currentThread() == this.serverThread; }

	/**
	 * The compression treshold. If the packet is larger than the specified amount of bytes, it will be
	 * compressed
	 */
	public int getNetworkCompressionTreshold() { return 256; }
}
