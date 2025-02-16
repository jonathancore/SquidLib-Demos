package com.squidpony.gwt;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.gwt.GwtApplication;
import com.badlogic.gdx.backends.gwt.GwtApplicationConfiguration;
import com.squidpony.DawnlikeDemo;

import static com.squidpony.DawnlikeDemo.*;

/** Launches the GWT application. */
public class GwtLauncher extends GwtApplication {
    @Override
    public GwtApplicationConfiguration getConfig() {
//        GwtApplicationConfiguration configuration = new GwtApplicationConfiguration(gridWidth * cellWidth * 2, gridHeight * cellHeight * 2);
        GwtApplicationConfiguration cfg = new GwtApplicationConfiguration(true);
        cfg.padVertical = 160;
        cfg.padHorizontal = 2;
        cfg.disableAudio = true;
        return cfg;
    }

    @Override
    public ApplicationListener createApplicationListener() {
        return new DawnlikeDemo();
    }
}
