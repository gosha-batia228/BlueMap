/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.cli;

import com.flowpowered.math.vector.Vector2i;
import de.bluecolored.bluemap.common.BlueMapService;
import de.bluecolored.bluemap.common.MissingResourcesException;
import de.bluecolored.bluemap.common.plugin.RegionFileWatchService;
import de.bluecolored.bluemap.common.rendermanager.RenderManager;
import de.bluecolored.bluemap.common.rendermanager.RenderTask;
import de.bluecolored.bluemap.common.rendermanager.WorldRegionRenderTask;
import de.bluecolored.bluemap.common.web.FileRequestHandler;
import de.bluecolored.bluemap.common.web.WebSettings;
import de.bluecolored.bluemap.core.BlueMap;
import de.bluecolored.bluemap.core.MinecraftVersion;
import de.bluecolored.bluemap.core.config.WebServerConfig;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.logger.LoggerLogger;
import de.bluecolored.bluemap.core.map.BmMap;
import de.bluecolored.bluemap.core.metrics.Metrics;
import de.bluecolored.bluemap.core.util.FileUtils;
import de.bluecolored.bluemap.core.webserver.HttpRequestHandler;
import de.bluecolored.bluemap.core.webserver.WebServer;
import org.apache.commons.cli.*;
import org.apache.commons.lang3.time.DurationFormatUtils;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class BlueMapCLI {
	
	public void renderMaps(BlueMapService blueMap, boolean watch, boolean forceRender, boolean forceGenerateWebapp) throws IOException, InterruptedException {
		
		//metrics report
		if (blueMap.getCoreConfig().isMetricsEnabled()) Metrics.sendReportAsync("cli");
		
		blueMap.createOrUpdateWebApp(forceGenerateWebapp);
		WebSettings webSettings = blueMap.updateWebAppSettings();

		//try load resources
		blueMap.getResourcePack();

		//create renderManager
		RenderManager renderManager = new RenderManager();

		//load maps
		Map<String, BmMap> maps = blueMap.getMaps();

		//watcher
		List<RegionFileWatchService> regionFileWatchServices = new ArrayList<>();
		if (watch) {
			for (BmMap map : maps.values()) {
				try {
					RegionFileWatchService watcher = new RegionFileWatchService(renderManager, map, true);
					watcher.start();
					regionFileWatchServices.add(watcher);
				} catch (IOException ex) {
					Logger.global.logError("Failed to create file-watcher for map: " + map.getId() +
										   " (This map might not automatically update)", ex);
				}
			}
		}

		//update all maps
		for (BmMap map : maps.values()) {
			for (Vector2i region : map.getWorld().listRegions()){
				renderManager.scheduleRenderTask(new WorldRegionRenderTask(map, region, forceRender));
			}
		}
		int totalRegions = renderManager.getScheduledRenderTasks().size();

		Logger.global.logInfo("Start " + (forceRender ? "rendering " : "updating ") + maps.size() + " maps (" + totalRegions + " regions, ~" + totalRegions * 1024L + " chunks)...");

		// start rendering
		long startTime = System.currentTimeMillis();
		renderManager.start(blueMap.getCoreConfig().getRenderThreadCount());

		Timer timer = new Timer("BlueMap-CLI-Timer", true);
		TimerTask updateInfoTask = new TimerTask() {
			@Override
			public void run() {
				List<RenderTask> tasks = renderManager.getScheduledRenderTasks();

				double remainingRegions = tasks.size();
				if (!tasks.isEmpty()) remainingRegions -= tasks.get(0).estimateProgress();

				double progress = 1 - (remainingRegions / totalRegions);

				long now = System.currentTimeMillis();
				long elapsedTime = now - startTime;
				long etr = (long) ((elapsedTime / progress) * (1 - progress));
				String etrDurationString = DurationFormatUtils.formatDuration(etr, "HH:mm:ss");

				Logger.global.logInfo("Rendering: " + (Math.round(progress * 100000) / 1000.0) + "% (ETR: " + etrDurationString + ")");
			}
		};
		timer.scheduleAtFixedRate(updateInfoTask, TimeUnit.SECONDS.toMillis(10), TimeUnit.SECONDS.toMillis(10));

		TimerTask saveTask = new TimerTask() {
			@Override
			public void run() {
				for (BmMap map : maps.values()) {
					map.save();
				}
			}
		};
		timer.scheduleAtFixedRate(saveTask, TimeUnit.MINUTES.toMillis(2), TimeUnit.MINUTES.toMillis(2));

		Runnable shutdown = () -> {
			Logger.global.logInfo("Stopping...");
			updateInfoTask.cancel();
			saveTask.cancel();
			renderManager.stop();

			for (RegionFileWatchService watcher : regionFileWatchServices) {
				watcher.close();
			}
			regionFileWatchServices.clear();

			try {
				renderManager.awaitShutdown();
			} catch (InterruptedException e) {
				Logger.global.logError("Unexpected interruption: ", e);
			}

			Logger.global.logInfo("Saving...");
			saveTask.run();

			Logger.global.logInfo("Stopped.");
		};

		Thread shutdownHook = new Thread(shutdown);
		Runtime.getRuntime().addShutdownHook(shutdownHook);

		// wait until done, then shutdown if not watching
		renderManager.awaitIdle();
		Logger.global.logInfo("Your maps are now up-to-date!");

		if (!watch) {
			Runtime.getRuntime().removeShutdownHook(shutdownHook);
			shutdown.run();
		}
	}
	
	public void startWebserver(BlueMapService blueMap, boolean verbose) throws IOException {
		Logger.global.logInfo("Starting webserver ...");
		
		WebServerConfig config = blueMap.getWebServerConfig();
		FileUtils.mkDirs(config.getWebRoot());
		HttpRequestHandler requestHandler = new FileRequestHandler(config.getWebRoot().toPath(), "BlueMap v" + BlueMap.VERSION);

		WebServer webServer = new WebServer(
				config.getWebserverBindAddress(),
				config.getWebserverPort(),
				config.getWebserverMaxConnections(),
				requestHandler,
				verbose
		);
		webServer.start();
	}
	
	public static void main(String[] args) {
		CommandLineParser parser = new DefaultParser();
		
		BlueMapCLI cli = new BlueMapCLI();
		BlueMapService blueMap = null;
		
		try {
			CommandLine cmd = parser.parse(BlueMapCLI.createOptions(), args, false);
			
			if (cmd.hasOption("l")) {
				Logger.global = LoggerLogger.getInstance();
				((LoggerLogger) Logger.global).addFileHandler(cmd.getOptionValue("l"), cmd.hasOption("a"));
			}
			
			//help
			if (cmd.hasOption("h")) {
				BlueMapCLI.printHelp();
				return;
			}
			
			//config folder
			File configFolder = new File(".");
			if (cmd.hasOption("c")) {
				configFolder = new File(cmd.getOptionValue("c"));
				FileUtils.mkDirs(configFolder);
			}
			
			//minecraft version
			MinecraftVersion version = MinecraftVersion.getLatest();
			if (cmd.hasOption("v")) {
				String versionString = cmd.getOptionValue("v");
				try {
					version = MinecraftVersion.fromVersionString(versionString);
				} catch (IllegalArgumentException e) {
					Logger.global.logWarning("Could not determine a version from the provided version-string: '" + versionString + "'");
					System.exit(1);
					return;
				}
			}

			blueMap = new BlueMapService(version, configFolder);
			boolean noActions = true;

			if (cmd.hasOption("w")) {
				noActions = false;
				
				cli.startWebserver(blueMap, cmd.hasOption("b"));
				Thread.sleep(1000); //wait a second to let the webserver start, looks nicer in the log if anything comes after that
			}
			
			if (cmd.hasOption("r")) {
				noActions = false;

				boolean watch = cmd.hasOption("u");
				boolean force = cmd.hasOption("f");
				boolean generateWebappFiles = cmd.hasOption("g");
				cli.renderMaps(blueMap, watch, force, generateWebappFiles);
			} else {
				if (cmd.hasOption("g")) {
					noActions = false;
					blueMap.createOrUpdateWebApp(true);
				}
				if (cmd.hasOption("s")) {
					noActions = false;
					blueMap.updateWebAppSettings();
				}
			}
			
			// if nothing has been defined to do
			if (noActions) {
				
				if (
						!blueMap.getCoreConfigFile().exists() ||
						!blueMap.getRenderConfigFile().exists() ||
						!blueMap.getWebServerConfigFile().exists()
				) {
					Logger.global.logInfo("Generating default config files for you, here: " + configFolder.getCanonicalPath().toString() + "\n");
				}
				
				//generate all configs
				blueMap.getCoreConfig();
				blueMap.getRenderConfig();
				blueMap.getWebServerConfig();

				//create resourcepacks folder
				FileUtils.mkDirs(new File(configFolder, "resourcepacks"));
				
				//print help
				BlueMapCLI.printHelp();
				System.exit(1);
				return;
			}
			
		} catch (MissingResourcesException e) {
			Logger.global.logWarning("BlueMap is missing important resources!");
			Logger.global.logWarning("You must accept the required file download in order for BlueMap to work!");
			try { Logger.global.logWarning("Please check: " + blueMap.getCoreConfigFile().getCanonicalPath()); } catch (NullPointerException | IOException ignored) {}
			System.exit(2);
			return;
		} catch (ParseException e) {
			Logger.global.logError("Failed to parse provided arguments!", e);
			BlueMapCLI.printHelp();
			System.exit(1);
			return;
		} catch (IOException e) {
			Logger.global.logError("An IO-error occurred!", e);
			System.exit(1);
			return;
		} catch (InterruptedException ex) {
			System.exit(1);
			return;
		} catch (RuntimeException e) {
			Logger.global.logError("An unexpected error occurred!", e);
			System.exit(1);
			return;
		}
	}
	
	private static Options createOptions() {
		Options options = new Options();
		
		options.addOption("h", "help", false, "Displays this message");

		options.addOption(
				Option.builder("c")
				.longOpt("config")
				.hasArg()
				.argName("config-folder")
				.desc("Sets path of the folder containing the configuration-files to use (configurations will be generated here if they don't exist)")
				.build()
			);
		
		options.addOption(
				Option.builder("v")
				.longOpt("mc-version")
				.hasArg()
				.argName("version")
				.desc("Sets the minecraft-version, used e.g. to load resource-packs correctly. Defaults to the latest compatible version.")
				.build()
			);
		
		options.addOption(
				Option.builder("l")
				.longOpt("log-file")
				.hasArg()
				.argName("file-name")
				.desc("Sets a file to save the log to. If not specified, no log will be saved.")
				.build()
			);
		options.addOption("a", "append", false, "Causes log save file to be appended rather than replaced.");

		options.addOption("w", "webserver", false, "Starts the web-server, configured in the 'webserver.conf' file");
		options.addOption("b", "verbose", false, "Causes the web-server to log requests to the console");

		options.addOption("g", "generate-webapp", false, "Generates the files for the web-app to the folder, configured in the 'render.conf' file (this is done automatically when rendering if the 'index.html' file in the webroot can't be found)");
		options.addOption("s", "generate-websettings", false, "Generates the settings for the web-app, using the settings from the 'render.conf' file (this is done automatically when rendering)");

		options.addOption("r", "render", false, "Renders the maps configured in the 'render.conf' file");
		options.addOption("f", "force-render", false, "Forces rendering everything, instead of only rendering chunks that have been modified since the last render");

		options.addOption("u", "watch", false, "Watches for file-changes after rendering and updates the map");
		
		return options;
	}

	private static void printHelp() {
		HelpFormatter formatter = new HelpFormatter();
		
		String filename = "bluemap-cli.jar";
		try {
			File file = new File(BlueMapCLI.class.getProtectionDomain()
					.getCodeSource()
					.getLocation()
					.getPath());
			
			if (file.isFile()) {
				try {
					filename = "." + File.separator + new File("").getCanonicalFile().toPath().relativize(file.toPath()).toString();
				} catch (IllegalArgumentException ex) {
					filename = file.getAbsolutePath();
				}
			}
		} catch (IOException ignore) {}
		
		String command = "java -jar " + filename;
		
		StringBuilder footer = new StringBuilder();
		footer.append("Examples:\n\n");
		footer.append(command + " -c './config/'\n");
		footer.append("Generates the default/example configurations in a folder named 'config' if they are not already present\n\n");
		footer.append(command + " -r\n");
		footer.append("Render the configured maps\n\n");
		footer.append(command + " -w\n");
		footer.append("Start only the webserver without doing anything else\n\n");
		footer.append(command + " -gs\n");
		footer.append("Generate the web-app and settings without starting a render\n\n");
		
		formatter.printHelp(command + " [options]", "\nOptions:", createOptions(), "\n" + footer.toString());
	}
	
}
