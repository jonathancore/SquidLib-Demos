package com.github.yellowstonegames.lwjgl3;

import static com.github.yellowstonegames.DungeonDemo.GRID_WIDTH;
import static com.github.yellowstonegames.DungeonDemo.GRID_HEIGHT;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.github.yellowstonegames.DungeonDemo;

/** Launches the desktop (LWJGL3) application. */
public class Lwjgl3Launcher {
	public static void main(String[] args) {
		createApplication();
	}

	private static Lwjgl3Application createApplication() {
		return new Lwjgl3Application(new DungeonDemo(), getDefaultConfiguration());
	}

	private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
		Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
		configuration.setResizable(true);
		configuration.useVsync(true);
		//// Limits FPS to the refresh rate of the currently active monitor.
		configuration.setForegroundFPS(Lwjgl3ApplicationConfiguration.getDisplayMode().refreshRate);
		//// If you remove the above line and set Vsync to false, you may get unlimited FPS, which can be
		//// useful for testing performance, but can also be very stressful to some hardware.
		//// You may also need to configure GPU drivers to fully disable Vsync; this can cause screen tearing.

		configuration.setTitle("SquidSquad Dungeon Demo");
		// these are constants in the main game class; they should match your
		// initial viewport size in pixels before it gets resized to fullscreen.
        configuration.setWindowedMode(GRID_WIDTH * 16, GRID_HEIGHT * 16);
        configuration.disableAudio(true);
		return configuration;
	}
}