package com.github.yellowstonegames.gwt;

import static com.github.yellowstonegames.DungeonDemo.GRID_WIDTH;
import static com.github.yellowstonegames.DungeonDemo.GRID_HEIGHT;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.gwt.GwtApplication;
import com.badlogic.gdx.backends.gwt.GwtApplicationConfiguration;
import com.github.yellowstonegames.DungeonDemo;

/** Launches the GWT application. */
public class GwtLauncher extends GwtApplication {
		@Override
		public GwtApplicationConfiguration getConfig () {
			// Resizable application, uses available space in browser with no padding:
//			GwtApplicationConfiguration cfg = new GwtApplicationConfiguration(true);
//			cfg.padVertical = 0;
//			cfg.padHorizontal = 0;
//			return cfg;
			// If you want a fixed size application, comment out the above resizable section,
			// and uncomment below:
			GwtApplicationConfiguration cfg = new GwtApplicationConfiguration(GRID_WIDTH * 16, GRID_HEIGHT * 16);
			return cfg;
		}

		@Override
		public ApplicationListener createApplicationListener () {
			return new DungeonDemo();
		}
}
