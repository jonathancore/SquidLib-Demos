package com.squidpony;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.*;
import com.badlogic.gdx.utils.viewport.ScalingViewport;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import squidpony.ArrayTools;
import squidpony.FakeLanguageGen;
import squidpony.squidai.DijkstraMap;
import squidpony.squidgrid.FOV;
import squidpony.squidgrid.Measurement;
import squidpony.squidgrid.Radius;
import squidpony.squidgrid.mapping.DungeonGenerator;
import squidpony.squidgrid.mapping.DungeonUtility;
import squidpony.squidmath.Coord;
import squidpony.squidmath.GWTRNG;
import squidpony.squidmath.GreasedRegion;
import squidpony.squidmath.OrderedMap;

import java.util.ArrayList;
import java.util.List;

import static com.badlogic.gdx.Input.Keys.*;

/**
 */
public class DawnlikeDemo extends ApplicationAdapter {
    private static final float DURATION = 0.375f;
    private long startTime;
    private enum Phase {WAIT, PLAYER_ANIM, MONSTER_ANIM}
    private SpriteBatch batch, soloBatch;
    private FrameBuffer buffer;
    private ShaderProgram shader;
    private boolean scalingShader = false;
    private Phase phase = Phase.WAIT;
    private long animationStart;

    // random number generator, optimized for when you build for the web browser (with GWT)
    private GWTRNG rng;
    
    // Stores all images we use here efficiently, as well as the font image 
    private TextureAtlas atlas;
    // This maps chars, such as '#', to specific images, such as a pillar.
    private IntMap<TextureAtlas.AtlasRegion> charMapping;
    
    private DungeonGenerator dungeonGen;
    private char[][] decoDungeon, bareDungeon, lineDungeon;
    // these use packed float colors, which avoid the overhead of creating new Color objects
    private float[][] colors, bgColors;
    private Coord player;
    private final int fovRange = 8;
    private Vector3 pos = new Vector3();

    //Here, gridHeight refers to the total number of rows to be displayed on the screen.
    //We're displaying 32 rows of dungeon, then 1 more row of player stats, like current health.
    //gridHeight is 32 because that variable will be used for generating the dungeon (the actual size of the dungeon
    //will be double gridWidth and double gridHeight), and determines how much off the dungeon is visible at any time.
    //The bonusHeight is the number of additional rows that aren't handled like the dungeon rows and are shown in a
    //separate area; here we use one row for player stats. The gridWidth is 48, which means we show 48 grid spaces
    //across the whole screen, but the actual dungeon is larger. The cellWidth and cellHeight are each 16, which will
    //match the starting dimensions of a cell in pixels, but won't be stuck at that value because a PixelPerfectViewport
    //is used and will increase the cell size in multiples of 16 when the window is resized. While gridWidth and
    //gridHeight are measured in spaces on the grid, cellWidth and cellHeight are the initial pixel dimensions of one
    //cell; resizing the window can make the units cellWidth and cellHeight use smaller or larger than a pixel.

    /** In number of cells */
    public static final int gridWidth = 32;
    /** In number of cells */
    public static final int gridHeight = 24;

    /** In number of cells */
    public static final int bigWidth = gridWidth * 2;
    /** In number of cells */
    public static final int bigHeight = gridHeight * 2;

//    /** In number of cells */
//    public static final int bonusHeight = 0;
    /** The pixel width of a cell */
    public static final int cellWidth = 16;
    /** The pixel height of a cell */
    public static final int cellHeight = 16;
    
    private boolean onGrid(int screenX, int screenY)
    {
        return screenX >= 0 && screenX < bigWidth && screenY >= 0 && screenY < bigHeight;
    }


    private InputProcessor input;
    private long lastDrawTime = 0;
    private Color bgColor;
    private BitmapFont font;
    private Viewport mainViewport, basicViewport;
    private Camera camera, basicCamera;
    
    private OrderedMap<Coord, AnimatedGlider> monsters;
    private DijkstraMap getToPlayer, playerToCursor;
    private Coord cursor;
    private List<Coord> toCursor;
    private List<Coord> awaitedMoves;
    private String lang;
    private double[][] resistance;
    private double[][] visible;
    private TextureAtlas.AtlasRegion solid;
    private int health = 9;
    
    // GreasedRegion is a hard-to-explain class, but it's an incredibly useful one for map generation and many other
    // tasks; it stores a region of "on" cells where everything not in that region is considered "off," and can be used
    // as a Collection of Coord points. However, it's more than that! Because of how it is implemented, it can perform
    // bulk operations on as many as 64 points at a time, and can efficiently do things like expanding the "on" area to
    // cover adjacent cells that were "off", retracting the "on" area away from "off" cells to shrink it, getting the
    // surface ("on" cells that are adjacent to "off" cells) or fringe ("off" cells that are adjacent to "on" cells),
    // and generally useful things like picking a random point from all "on" cells.
    // Here, we use a GreasedRegion to store all floors that the player can walk on, a small rim of cells just beyond
    // the player's vision that blocks pathfinding to areas we can't see a path to, and we also store all cells that we
    // have seen in the past in a GreasedRegion (in most roguelikes, there would be one of these per dungeon floor).
    private GreasedRegion floors, blockage, seen;
    private AnimatedGlider playerSprite;
    // libGDX can use a kind of packed float (yes, the number type) to efficiently store colors, but it also uses a
    // heavier-weight Color object sometimes; SquidLib has a large list of SColor objects that are often used as easy
    // predefined colors since SColor extends Color. SparseLayers makes heavy use of packed float colors internally,
    // but also allows Colors instead for most methods that take a packed float. Some cases, like very briefly-used
    // colors that are some mix of two other colors, are much better to create as packed floats from other packed
    // floats, usually using SColor.lerpFloatColors(), which avoids creating any objects. It's ideal to avoid creating
    // new objects (such as Colors) frequently for only brief usage, because this can cause temporary garbage objects to
    // build up and slow down the program while they get cleaned up (garbage collection, which is slower on Android).
    // Recent versions of SquidLib include the packed float literal in the JavaDocs for any SColor, along with previews
    // of that SColor as a background and foreground when used with other colors, plus more info like the hue,
    // saturation, and value of the color. Here we just use the packed floats directly from the SColor docs, but we also
    // mention what color it is in a line comment, which is a good habit so you can see a preview if needed.
    // The format used for the floats is a hex literal; these are explained at the bottom of this file, in case you
    // aren't familiar with them (they're a rather obscure feature of Java 5 and newer).
    private static final float
            FLOAT_WHITE = Color.WHITE.toFloatBits(), 
            FLOAT_BLACK = Color.BLACK.toFloatBits(),
            FLOAT_BLOOD = -0x1.564f86p125F,  // same result as SColor.PURE_CRIMSON.toFloatBits()
            FLOAT_LIGHTING = ColorTools.floatGetHSV(0.17f, 0.0625f, 0.875f, 1f),
//            FLOAT_LIGHTING = ColorTools.floatGetHSV(0.17f, 0.12f, 0.8f, 1f),
// -0x1.cff1fep126F, // same result as SColor.COSMIC_LATTE.toFloatBits()
            FLOAT_GRAY = ColorTools.floatGetHSV(0f, 0f, 0.25f, 1f);
                    //-0x1.7e7e7ep125F; // same result as SColor.CW_GRAY_BLACK.toFloatBits()
    // the player's color as a float
//    private float playerColor;
    @Override
    public void create () {
        Gdx.app.setLogLevel(Application.LOG_DEBUG);
        // Starting time for the game; other times are measured relative to this so they aren't huge numbers.
        startTime = TimeUtils.millis();
        // Gotta have a random number generator.
        // We can seed a GWTRNG, which is optimized for the HTML target, with any int or long
        // we want. You can also hash a String with CrossHash.hash64("Some seed") to get a
        // random-seeming long to use for a seed. CrossHash is preferred over String.hashCode()
        // because it can produce 64-bit seeds and String.hashCode() will only produce 32-bit
        // seeds; having more possible seeds means more maps and other procedural content
        // become possible. Here we don't seed the GWTRNG, so its seed will be random.
        rng = new GWTRNG(123456);
        //Some classes in SquidLib need access to a batch to render certain things, so it's a good idea to have one.
        batch = new SpriteBatch();
        soloBatch = new SpriteBatch(32);
        buffer = new FrameBuffer(Pixmap.Format.RGB888, cellWidth * gridWidth, cellHeight * gridHeight, false);

        shader = new ShaderProgram(Gdx.files.internal("xbr-lv3.vert.txt"), Gdx.files.internal("xbr-lv3.frag.txt"));
//        shader = new ShaderProgram(Gdx.files.internal("xpal.vert.txt"), Gdx.files.internal("xpal.frag.txt"));
        if (!shader.isCompiled()) {
            Gdx.app.error("Shader", shader.getLog());
            scalingShader = false;
        }
        else{
            batch.setShader(shader);
            scalingShader = true;
        }
        animationStart = TimeUtils.millis();
        
        mainViewport = new ScalingViewport(Scaling.fill, gridWidth * cellWidth, gridHeight * cellHeight);
        mainViewport.setScreenBounds(0, 0, gridWidth * cellWidth, gridHeight * cellHeight);
        camera = mainViewport.getCamera();
        camera.update();
        basicViewport = new ScreenViewport();
        basicCamera = basicViewport.getCamera();

        atlas = new TextureAtlas("Dawnlike.atlas");
        font = new BitmapFont(Gdx.files.internal("font.fnt"), atlas.findRegion("font"));
        font.setUseIntegerPositions(true);
        font.getData().markupEnabled = true;
        bgColors = ArrayTools.fill(FLOAT_BLACK, bigWidth, bigHeight);
        colors = ArrayTools.fill(FLOAT_WHITE, bigWidth, bigHeight);
        solid = atlas.findRegion("pixel");
        charMapping = new IntMap<>(64);

        charMapping.put('.', atlas.findRegion("day tile floor c"));
        charMapping.put(',', atlas.findRegion("brick clear pool center"      ));
        charMapping.put('~', atlas.findRegion("brick murky pool center"      ));
        charMapping.put('"', atlas.findRegion("dusk grass floor c"      ));
        charMapping.put('#', atlas.findRegion("lit brick wall center"     ));
        charMapping.put('+', atlas.findRegion("closed wooden door front")); //front
        charMapping.put('/', atlas.findRegion("open wooden door side"  )); //side
        charMapping.put('┌', atlas.findRegion("lit brick wall right down"            ));
        charMapping.put('└', atlas.findRegion("lit brick wall right up"            ));
        charMapping.put('┴', atlas.findRegion("lit brick wall left right up"           ));
        charMapping.put('┬', atlas.findRegion("lit brick wall left right down"           ));
        charMapping.put('─', atlas.findRegion("lit brick wall left right"            ));
        charMapping.put('│', atlas.findRegion("lit brick wall up down"            ));
        charMapping.put('├', atlas.findRegion("lit brick wall right up down"           ));
        charMapping.put('┼', atlas.findRegion("lit brick wall left right up down"          ));
        charMapping.put('┤', atlas.findRegion("lit brick wall left up down"           ));
        charMapping.put('┘', atlas.findRegion("lit brick wall left up"            ));
        charMapping.put('┐', atlas.findRegion("lit brick wall left down"            ));
        //This uses the seeded RNG we made earlier to build a procedural dungeon using a method that takes rectangular
        //sections of pre-drawn dungeon and drops them into place in a tiling pattern. It makes good winding dungeons
        //with rooms by default, but in the later call to dungeonGen.generate(), you can use a TilesetType such as
        //TilesetType.ROUND_ROOMS_DIAGONAL_CORRIDORS or TilesetType.CAVES_LIMIT_CONNECTIVITY to change the sections that
        //this will use, or just pass in a full 2D char array produced from some other generator, such as
        //SerpentMapGenerator, OrganicMapGenerator, or DenseRoomMapGenerator.
        dungeonGen = new DungeonGenerator(bigWidth, bigHeight, rng);
        //uncomment this next line to randomly add water to the dungeon in pools.
        dungeonGen.addWater(12);
        dungeonGen.addDoors(10, true);
        dungeonGen.addGrass(10);
        //decoDungeon is given the dungeon with any decorations we specified. (Here, we didn't, unless you chose to add
        //water to the dungeon. In that case, decoDungeon will have different contents than bareDungeon, next.)
        decoDungeon = dungeonGen.generate();
        //getBareDungeon provides the simplest representation of the generated dungeon -- '#' for walls, '.' for floors.
        bareDungeon = dungeonGen.getBareDungeon();
        //When we draw, we may want to use a nicer representation of walls. DungeonUtility has lots of useful methods
        //for modifying char[][] dungeon grids, and this one takes each '#' and replaces it with a box-drawing char.
        //The end result looks something like this, for a smaller 60x30 map:
        //
        // ┌───┐┌──────┬──────┐┌──┬─────┐   ┌──┐    ┌──────────┬─────┐
        // │...││......│......└┘..│.....│   │..├───┐│..........│.....└┐
        // │...││......│..........├──┐..├───┤..│...└┴────......├┐.....│
        // │...││.................│┌─┘..│...│..│...............││.....│
        // │...││...........┌─────┘│....│...│..│...........┌───┴┴───..│
        // │...│└─┐....┌───┬┘      │........│..│......─────┤..........│
        // │...└─┐│....│...│       │.......................│..........│
        // │.....││........└─┐     │....│..................│.....┌────┘
        // │.....││..........│     │....├─┬───────┬─┐......│.....│
        // └┬──..└┼───┐......│   ┌─┴─..┌┘ │.......│ │.....┌┴──┐..│
        //  │.....│  ┌┴─..───┴───┘.....└┐ │.......│┌┘.....└─┐ │..│
        //  │.....└──┘..................└─┤.......││........│ │..│
        //  │.............................│.......├┘........│ │..│
        //  │.............┌──────┐........│.......│...─┐....│ │..│
        //  │...........┌─┘      └──┐.....│..─────┘....│....│ │..│
        // ┌┴─────......└─┐      ┌──┘..................│..──┴─┘..└─┐
        // │..............└──────┘.....................│...........│
        // │............................┌─┐.......│....│...........│
        // │..│..│..┌┐..................│ │.......├────┤..──┬───┐..│
        // │..│..│..│└┬──..─┬───┐......┌┘ └┐.....┌┘┌───┤....│   │..│
        // │..├──┤..│ │.....│   │......├───┘.....│ │...│....│┌──┘..└──┐
        // │..│┌─┘..└┐└┬─..─┤   │......│.........└─┘...│....││........│
        // │..││.....│ │....│   │......│...............│....││........│
        // │..││.....│ │....│   │......│..┌──┐.........├────┘│..│.....│
        // ├──┴┤...│.└─┴─..┌┘   └┐....┌┤..│  │.....│...└─────┘..│.....│
        // │...│...│.......└─────┴─..─┴┘..├──┘.....│............└─────┤
        // │...│...│......................│........│..................│
        // │.......├───┐..................│.......┌┤.......┌─┐........│
        // │.......│   └──┐..┌────┐..┌────┤..┌────┘│.......│ │..┌──┐..│
        // └───────┘      └──┘    └──┘    └──┘     └───────┘ └──┘  └──┘
        //this is also good to compare against if the map looks incorrect, and you need an example of a correct map when
        //no parameters are given to generate().
        lineDungeon = DungeonUtility.hashesToLines(decoDungeon);

        resistance = DungeonUtility.generateSimpleResistances(decoDungeon);
        visible = new double[bigWidth][bigHeight];

        //Coord is the type we use as a general 2D point, usually in a dungeon.
        //Because we know dungeons won't be incredibly huge, Coord performs best for x and y values less than 256, but
        // by default it can also handle some negative x and y values (-3 is the lowest it can efficiently store). You
        // can call Coord.expandPool() or Coord.expandPoolTo() if you need larger maps to be just as fast.
        cursor = Coord.get(-1, -1);
        // here, we need to get a random floor cell to place the player upon, without the possibility of putting him
        // inside a wall. There are a few ways to do this in SquidLib. The most straightforward way is to randomly
        // choose x and y positions until a floor is found, but particularly on dungeons with few floor cells, this can
        // have serious problems -- if it takes too long to find a floor cell, either it needs to be able to figure out
        // that random choice isn't working and instead choose the first it finds in simple iteration, or potentially
        // keep trying forever on an all-wall map. There are better ways! These involve using a kind of specific storage
        // for points or regions, getting that to store only floors, and finding a random cell from that collection of
        // floors. The two kinds of such storage used commonly in SquidLib are the "packed data" as short[] produced by
        // CoordPacker (which use very little memory, but can be slow, and are treated as unchanging by CoordPacker so
        // any change makes a new array), and GreasedRegion objects (which use slightly more memory, tend to be faster
        // on almost all operations compared to the same operations with CoordPacker, and default to changing the
        // GreasedRegion object when you call a method on it instead of making a new one). Even though CoordPacker
        // sometimes has better documentation, GreasedRegion is generally a better choice; it was added to address
        // shortcomings in CoordPacker, particularly for speed, and the worst-case scenarios for data in CoordPacker are
        // no problem whatsoever for GreasedRegion. CoordPacker is called that because it compresses the information
        // for nearby Coords into a smaller amount of memory. GreasedRegion is called that because it encodes regions,
        // but is "greasy" both in the fatty-food sense of using more space, and in the "greased lightning" sense of
        // being especially fast. Both of them can be seen as storing regions of points in 2D space as "on" and "off."

        // Here we fill a GreasedRegion so it stores the cells that contain a floor, the '.' char, as "on."
        floors = new GreasedRegion(bareDungeon, '.');
        //player is, here, just a Coord that stores his position. In a real game, you would probably have a class for
        //creatures, and possibly a subclass for the player. The singleRandom() method on GreasedRegion finds one Coord
        //in that region that is "on," or -1,-1 if there are no such cells. It takes an RNG object as a parameter, and
        //if you gave a seed to the RNG constructor, then the cell this chooses will be reliable for testing. If you
        //don't seed the RNG, any valid cell should be possible.
        player = floors.singleRandom(rng);
        playerSprite = new AnimatedGlider(new Animation<>(DURATION,
                atlas.findRegions(rng.getRandomElement(Data.possibleCharacters)), Animation.PlayMode.LOOP), player);
//        playerColor = ColorTools.floatGetHSV(rng.nextFloat(), 1f, 1f, 1f);
//        playerSprite.setPackedColor(playerColor);
//        playerSprite.setPosition(player.x * cellWidth, player.y * cellHeight);
        // Uses shadowcasting FOV and reuses the visible array without creating new arrays constantly.
        FOV.reuseFOV(resistance, visible, player.x, player.y, 9.0, Radius.CIRCLE);
        // 0.0 is the upper bound (inclusive), so any Coord in visible that is more well-lit than 0.0 will _not_ be in
        // the blockage Collection, but anything 0.0 or less will be in it. This lets us use blockage to prevent access
        // to cells we can't see from the start of the move.
        blockage = new GreasedRegion(visible, 0.0);
        // Here we mark the initially seen cells as anything that wasn't included in the unseen "blocked" region.
        // We invert the copy's contents to prepare for a later step, which makes blockage contain only the cells that
        // are above 0.0, then copy it to save this step as the seen cells. We will modify seen later independently of
        // the blocked cells, so a copy is correct here. Most methods on GreasedRegion objects will modify the
        // GreasedRegion they are called on, which can greatly help efficiency on long chains of operations.
        seen = blockage.not().copy();
        // Here is one of those methods on a GreasedRegion; fringe8way takes a GreasedRegion (here, the set of cells
        // that are visible to the player), and modifies it to contain only cells that were not in the last step, but
        // were adjacent to a cell that was present in the last step. This can be visualized as taking the area just
        // beyond the border of a region, using 8-way adjacency here because we specified fringe8way instead of fringe.
        // We do this because it means pathfinding will only have to work with a small number of cells (the area just
        // out of sight, and no further) instead of all invisible cells when figuring out if something is currently
        // impossible to enter.
        blockage.fringe8way();
        floors.remove(player);
        int numMonsters = 100;
        monsters = new OrderedMap<>(numMonsters);
        for (int i = 0; i < numMonsters; i++) {
            Coord monPos = floors.singleRandom(rng);
            floors.remove(monPos);
            String enemy = rng.getRandomElement(Data.possibleEnemies);
            AnimatedGlider monster =
                    new AnimatedGlider(new Animation<>(DURATION,
                            atlas.findRegions(enemy), Animation.PlayMode.LOOP), monPos);
//            monster.setPackedColor(ColorTools.floatGetHSV(rng.nextFloat(), 0.75f, 0.8f, 0f));
            // new Color().fromHsv(rng.nextFloat(), 0.75f, 0.8f));
            monsters.put(monPos, monster);
        }
        //This is used to allow clicks or taps to take the player to the desired area.
        toCursor = new ArrayList<>(200);
        //When a path is confirmed by clicking, we draw from this List to find which cell is next to move into.
        awaitedMoves = new ArrayList<>(200);
        //DijkstraMap is the pathfinding swiss-army knife we use here to find a path to the latest cursor position.
        //DijkstraMap.Measurement is an enum that determines the possibility or preference to enter diagonals. Here, the
        //Measurement used is EUCLIDEAN, which allows 8 directions, but will prefer orthogonal moves unless diagonal
        //ones are clearly closer "as the crow flies." Alternatives are MANHATTAN, which means 4-way movement only, no
        //diagonals possible, and CHEBYSHEV, which allows 8 directions of movement at the same cost for all directions.
        playerToCursor = new DijkstraMap(bareDungeon, Measurement.EUCLIDEAN);
        getToPlayer = new DijkstraMap(decoDungeon, Measurement.EUCLIDEAN);
        //These next two lines mark the player as something we want paths to go to or from, and get the distances to the
        // player from all walkable cells in the dungeon.
        playerToCursor.setGoal(player);
        // DijkstraMap.partialScan only finds the distance to get to a cell if that distance is less than some limit,
        // which is 13 here. It also won't try to find distances through an impassable cell, which here is the blockage
        // GreasedRegion that contains the cells just past the edge of the player's FOV area.
        playerToCursor.partialScan(13, blockage);


        bgColor = Color.BLACK;


        lang = '"' + FakeLanguageGen.DEMONIC.sentence(rng, 4, 7,
                new String[]{",", ",", ",", " -"}, new String[]{"...\"", ", heh...\"", ", nyehehe...\"",  "!\"", "!\"", "!\"", "!\" *PTOOEY!*",}, 0.2);

        // this is a big one.
        // SquidInput can be constructed with a KeyHandler (which just processes specific keypresses), a SquidMouse
        // (which is given an InputProcessor implementation and can handle multiple kinds of mouse move), or both.
        // keyHandler is meant to be able to handle complex, modified key input, typically for games that distinguish
        // between, say, 'q' and 'Q' for 'quaff' and 'Quip' or whatever obtuse combination you choose. The
        // implementation here handles hjkl keys (also called vi-keys), numpad, arrow keys, and wasd for 4-way movement.
        // Shifted letter keys produce capitalized chars when passed to KeyHandler.handle(), but we don't care about
        // that so we just use two case statements with the same body, i.e. one for 'A' and one for 'a'.
        // You can also set up a series of future moves by clicking within FOV range, using mouseMoved to determine the
        // path to the mouse position with a DijkstraMap (called playerToCursor), and using touchUp to actually trigger
        // the event when someone clicks.
        input = new InputAdapter() {
            @Override
            public boolean keyUp(int keycode) {
                switch (keycode) {
                    case UP:
                    case W:
                    case NUMPAD_8:
                        toCursor.clear();
                        //+1 is up on the screen
                        awaitedMoves.add(player.translate(0, 1));
                        break;
                    case DOWN:
                    case S:
                    case NUMPAD_2:
                        toCursor.clear();
                        //-1 is down on the screen
                        awaitedMoves.add(player.translate(0, -1));
                        break;
                    case LEFT:
                    case A:
                    case NUMPAD_4:
                        toCursor.clear();
                        awaitedMoves.add(player.translate(-1, 0));
                        break;
                    case RIGHT:
                    case D:
                    case NUMPAD_6:
                        toCursor.clear();
                        awaitedMoves.add(player.translate(1, 0));
                        break;
                    case NUMPAD_1:
                        toCursor.clear();
                        awaitedMoves.add(player.translate(-1, -1));
                        break;
                    case NUMPAD_3:
                        toCursor.clear();
                        awaitedMoves.add(player.translate(1, -1));
                        break;
                    case NUMPAD_7:
                        toCursor.clear();
                        awaitedMoves.add(player.translate(-1, 1));
                        break;
                    case NUMPAD_9:
                        toCursor.clear();
                        awaitedMoves.add(player.translate(1, 1));
                        break;
                    case PERIOD:
                    case NUMPAD_5:
                        toCursor.clear();
                        awaitedMoves.add(player);
                        break;
                    case B:
                        batch.setShader(scalingShader ? null : shader);
                        scalingShader = !scalingShader;
                        break;
                    case P:
                        DungeonUtility.debugPrint(decoDungeon);
                        break;
                    case ESCAPE:
                        Gdx.app.exit();
                        break;
                }
                return true;
            }

            // if the user clicks and mouseMoved hasn't already assigned a path to toCursor, then we call mouseMoved
            // ourselves and copy toCursor over to awaitedMoves.
            @Override
            public boolean touchUp(int screenX, int screenY, int pointer, int button) {
                pos.set(screenX, screenY, 0f);
                mainViewport.unproject(pos);
                if (onGrid(MathUtils.floor(pos.x) >> 4, MathUtils.floor(pos.y) >> 4)) {
                    mouseMoved(screenX, screenY);
                    awaitedMoves.addAll(toCursor);
                    return true;
                }
                return false;
            }

            @Override
            public boolean touchDragged(int screenX, int screenY, int pointer) {
                return mouseMoved(screenX, screenY);
            }

            // causes the path to the mouse position to become highlighted (toCursor contains a list of Coords that
            // receive highlighting). Uses DijkstraMap.findPathPreScanned() to find the path, which is rather fast.
            @Override
            public boolean mouseMoved(int screenX, int screenY) {
                if(!awaitedMoves.isEmpty())
                    return false;
                pos.set(screenX, screenY, 0f);
                mainViewport.unproject(pos);
                if (onGrid(screenX = MathUtils.floor(pos.x) >> 4, screenY = flipY(MathUtils.floor(pos.y) >> 4))) {
                    // we also need to check if screenX or screenY is the same cell.
                    if (cursor.x == screenX && cursor.y == screenY) {
                        return false;
                    }
                    cursor = Coord.get(screenX, screenY);
                    // This uses DijkstraMap.findPathPreScannned() to get a path as a List of Coord from the current
                    // player position to the position the user clicked on. The "PreScanned" part is an optimization
                    // that's special to DijkstraMap; because the part of the map that is viable to move into has
                    // already been fully analyzed by the DijkstraMap.partialScan() method at the start of the
                    // program, and re-calculated whenever the player moves, we only need to do a fraction of the
                    // work to find the best path with that info.
                    toCursor = playerToCursor.findPathPreScanned(cursor);
                    // findPathPreScanned includes the current cell (goal) by default, which is helpful when
                    // you're finding a path to a monster or loot, and want to bump into it, but here can be
                    // confusing because you would "move into yourself" as your first move without this.
                    // Getting a sublist avoids potential performance issues with removing from the start of an
                    // ArrayList, since it keeps the original list around and only gets a "view" of it.
                    if (!toCursor.isEmpty()) {
                        toCursor = toCursor.subList(1, toCursor.size());
                    }
                }
                return false;
            }
        };
        Gdx.input.setInputProcessor(input);
    }
    
    /**
     * Move the player if he isn't bumping into a wall or trying to go off the map somehow.
     * In a fully-fledged game, this would not be organized like this, but this is a one-file demo.
     * @param newX
     * @param newY
     */
    private void move(int newX, int newY) {
        if (health <= 0) return;
        if (newX >= 0 && newY >= 0 && newX < bigWidth && newY < bigHeight
                && bareDungeon[newX][newY] != '#') {
            // '+' is a door.
            if (lineDungeon[newX][newY] == '+') {
                decoDungeon[newX][newY] = '/';
                lineDungeon[newX][newY] = '/';
                // changes to the map mean the resistances for FOV need to be regenerated.
                resistance = DungeonUtility.generateSimpleResistances(decoDungeon);
                // recalculate FOV, store it in fovmap for the render to use.
                FOV.reuseFOV(resistance, visible, player.x, player.y, fovRange, Radius.CIRCLE);
                blockage.refill(visible, 0.0);
                seen.or(blockage.not());
                blockage.fringe8way();
            } else {
                // recalculate FOV, store it in fovmap for the render to use.
                FOV.reuseFOV(resistance, visible, newX, newY, fovRange, Radius.CIRCLE);
                blockage.refill(visible, 0.0);
                seen.or(blockage.not());
                blockage.fringe8way();
                playerSprite.start = player;
                playerSprite.end = (player = Coord.get(newX, newY));
                playerSprite.change = 0f;
                phase = Phase.PLAYER_ANIM;
                animationStart = TimeUtils.millis();
                // if a monster was at the position we moved into, and so was successfully removed...
                if(monsters.containsKey(player))
                {
                    monsters.remove(player);
                    for (int x = -1; x <= 1; x++) {
                        for (int y = -1; y <= 1; y++) {
                            if(rng.nextBoolean()) 
                                bgColors[newX+x][newY+y] = FLOAT_BLOOD;
                        }
                    }
                }
            }
            phase = Phase.PLAYER_ANIM;
        }
    }

    private void postMove()
    {
        phase = Phase.MONSTER_ANIM;
        Coord[] playerArray = {player};
        // in some cases you can use keySet() to get a Set of keys, but that makes a read-only view, and we want
        // a copy of the key set that we can edit (so monsters don't move into each others' spaces)
//        OrderedSet<Coord> monplaces = monsters.keysAsOrderedSet();
        int monCount = monsters.size();

        // recalculate FOV, store it in fovmap for the render to use.
        FOV.reuseFOV(resistance, visible, player.x, player.y, fovRange, Radius.CIRCLE);
        blockage.refill(visible, 0.0);
        seen.or(blockage.not());
        blockage.fringe8way();
        // handle monster turns
        ArrayList<Coord> nextMovePositions;
        for(int ci = 0; ci < monCount; ci++)
        {
            Coord pos = monsters.firstKey();
            AnimatedGlider mon = monsters.removeFirst();
            // monster values are used to store their aggression, 1 for actively stalking the player, 0 for not.
            if (visible[pos.x][pos.y] > 0.1) {
                getToPlayer.clearGoals();
                nextMovePositions = getToPlayer.findPath(1, 7, monsters.keySet(), null, pos, playerArray);
                if (nextMovePositions != null && !nextMovePositions.isEmpty()) {
                    Coord tmp = nextMovePositions.get(0);
                    // if we would move into the player, instead damage the player and give newMons the current
                    // position of this monster.
                    if (tmp.x == player.x && tmp.y == player.y) {
                        // not sure if this stays red for very long
                        playerSprite.color = (FLOAT_BLOOD);
                        health--;
                        // make sure the monster is still actively stalking/chasing the player
                        monsters.put(pos, mon);
                    }
                    // otherwise store the new position in newMons.
                    else {
                        // alter is a method on OrderedMap and OrderedSet that changes a key in-place
                        monsters.alter(pos, tmp);
                        mon.start = pos;
                        mon.end = tmp;
                        mon.change = 0f;
                        //display.slide(mon, pos.x, pos.y, tmp.x, tmp.y, 0.125f, null);
                        monsters.put(tmp, mon);
                    }
                } else {
                    monsters.put(pos, mon);
                }
            }
            else
            {
                monsters.put(pos, mon);
            }
        }

    }


    /**
     * Draws the map, applies any highlighting for the path to the cursor, and then draws the player.
     */
    public void putMap()
    {
        final float time = TimeUtils.timeSinceMillis(startTime) * 0.001f;
        //In many other situations, you would clear the drawn characters to prevent things that had been drawn in the
        //past from affecting the current frame. This isn't a problem here, but would probably be an issue if we had
        //monsters running in and out of our vision. If artifacts from previous frames show up, uncomment the next line.
        //display.clear();
        for (int i = 0; i < bigWidth; i++) {
            for (int j = 0; j < bigHeight; j++) {
                if(visible[i][j] > 0.0) {
                    pos.set(i * cellWidth, flipY(j) * cellHeight, 0f);
                    batch.setPackedColor(toCursor.contains(Coord.get(i, j))
                            ? ColorTools.lerpFloatColors(bgColors[i][j], FLOAT_WHITE, 0.9f)
                            : ColorTools.lerpFloatColors(bgColors[i][j], FLOAT_LIGHTING, (float)visible[i][j] * 0.75f + 0.25f));
                    if(lineDungeon[i][j] == '/' || lineDungeon[i][j] == '+') // doors expect a floor drawn beneath them
                        batch.draw(charMapping.get('.', solid), pos.x, pos.y, cellWidth, cellHeight);
                    batch.draw(charMapping.get(lineDungeon[i][j], solid), pos.x, pos.y, cellWidth, cellHeight);
                } else if(seen.contains(i, j)) {
                    pos.set(i * cellWidth, flipY(j) * cellHeight, 0f);
                    batch.setPackedColor(ColorTools.lerpFloatColors(bgColors[i][j], FLOAT_GRAY, 0.7f));
                    if(lineDungeon[i][j] == '/' || lineDungeon[i][j] == '+') // doors expect a floor drawn beneath them
                        batch.draw(charMapping.get('.', solid), pos.x, pos.y, cellWidth, cellHeight);
                    batch.draw(charMapping.get(lineDungeon[i][j], solid), pos.x, pos.y, cellWidth, cellHeight);
                }
            }
        }
        batch.setPackedColor(FLOAT_WHITE);
        AnimatedGlider monster;
        for (int i = 0; i < bigWidth; i++) {
            for (int j = 0; j < bigHeight; j++) {
                if (visible[i][j] > 0.0) {
                    if ((monster = monsters.get(Coord.get(i, j))) != null) {
                        batch.draw(monster.animate(time), monster.getX() * cellWidth, flipY(monster.getY()) * cellHeight);
                    }
                }
            }
        }
        batch.draw(playerSprite.animate(time), playerSprite.getX() * cellWidth, flipY(playerSprite.getY()) * cellHeight);
        Gdx.graphics.setTitle(Gdx.graphics.getFramesPerSecond() + " FPS");
    }
    @Override
    public void render () {
        if(!scalingShader) {
            buffer.begin();
        }
        ScreenUtils.clear(bgColor);

        // center the camera on the player's position
        camera.position.x = playerSprite.getX() * cellWidth;
        camera.position.y =  flipY(playerSprite.getY()) * cellHeight;
        camera.update();
        mainViewport.apply(false);
        batch.setProjectionMatrix(camera.combined);
        if(scalingShader) {
            shader.bind(); // prevents an OpenGL error, though it runs without this line
            shader.setUniformf("TextureSize", 2048f, 1024f);
        }
        batch.begin();
        
        // you done bad. you done real bad.
        if (health <= 0) {
            // still need to display the map, then write over it with a message.
            putMap();
            float wide = gridWidth * cellWidth,
                    x = playerSprite.getX() * cellWidth - wide * 0.5f,
                    y = flipY(playerSprite.getY()) * cellHeight;
            font.draw(batch, "[RED]YOUR CRAWL IS OVER![WHITE]", x, y + 32, wide, Align.center, true);
            font.draw(batch, "A monster sniffs your corpse and says,", x, y + 16, wide, Align.center, true);
            font.draw(batch, lang, x, y, wide, Align.center, true);
            font.draw(batch, "[LIGHT_GRAY]q[WHITE] to quit.", x, y - 32, wide, Align.center, true);
            batch.end();
            if(!scalingShader)
            {
                buffer.end();
                soloBatch.begin();
                soloBatch.draw(buffer.getColorBufferTexture(), 0, 0);
                soloBatch.end();
            }
            if(Gdx.input.isKeyPressed(Q))
                Gdx.app.exit();
            return;
        }

        // need to display the map every frame, since we clear the screen to avoid artifacts.
        putMap();
        
        if(phase == Phase.MONSTER_ANIM) {
            float t = TimeUtils.timeSinceMillis(animationStart) * 0x1p-7f;
            for (int i = 0; i < monsters.size(); i++) {
                monsters.getAt(i).change = t;
            }
            if (t >= 1f) {
                phase = Phase.WAIT;
                if (!awaitedMoves.isEmpty()) {
                    Coord m = awaitedMoves.remove(0);
                    if (!toCursor.isEmpty())
                        toCursor.remove(0);
                    move(m.x, m.y);
                }
            }
        }
        else if(phase == Phase.WAIT && !awaitedMoves.isEmpty())
        {
            Coord m = awaitedMoves.remove(0);
            if (!toCursor.isEmpty())
                toCursor.remove(0);
            move(m.x, m.y);
        }
        else if(phase == Phase.PLAYER_ANIM) {
            playerSprite.change = TimeUtils.timeSinceMillis(animationStart) * 0.008f;
            if (playerSprite.change >= 1f) {
                phase = Phase.MONSTER_ANIM;
                animationStart = TimeUtils.millis();
                postMove();
                // this only happens if we just removed the last Coord from awaitedMoves, and it's only then that we need to
                // re-calculate the distances from all cells to the player. We don't need to calculate this information on
                // each part of a many-cell move (just the end), nor do we need to calculate it whenever the mouse moves.
                if (awaitedMoves.isEmpty()) {
                    // the next two lines remove any lingering data needed for earlier paths
                    playerToCursor.clearGoals();
                    playerToCursor.resetMap();
                    // the next line marks the player as a "goal" cell, which seems counter-intuitive, but it works because all
                    // cells will try to find the distance between themselves and the nearest goal, and once this is found, the
                    // distances don't change as long as the goals don't change. Since the mouse will move and new paths will be
                    // found, but the player doesn't move until a cell is clicked, the "goal" is the non-changing cell, so the
                    // player's position, and the "target" of a pathfinding method like DijkstraMap.findPathPreScanned() is the
                    // currently-moused-over cell, which we only need to set where the mouse is being handled.
                    playerToCursor.setGoal(player);
                    // DijkstraMap.partialScan only finds the distance to get to a cell if that distance is less than some limit,
                    // which is 13 here. It also won't try to find distances through an impassable cell, which here is the blockage
                    // GreasedRegion that contains the cells just past the edge of the player's FOV area.
                    playerToCursor.partialScan(13, blockage);
                }
            }
        }
        pos.set(10,Gdx.graphics.getHeight() * 0xFp-4f, 0);
        mainViewport.unproject(pos);
        font.draw(batch, "Current Health: [RED]" + health + "[WHITE] at "
                + Gdx.graphics.getFramesPerSecond() + " FPS", pos.x, pos.y);
        batch.end();
        if(!scalingShader)
        {
            buffer.end();
            ScreenUtils.clear(bgColor);
            basicViewport.apply(true);
            soloBatch.setProjectionMatrix(basicCamera.combined);
            soloBatch.begin();
            Texture tex = buffer.getColorBufferTexture();
            tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            soloBatch.draw(tex, 0, Gdx.graphics.getHeight(), Gdx.graphics.getWidth(), -Gdx.graphics.getHeight());
            soloBatch.end();
        }
    }
    @Override
	public void resize(int width, int height) {
		super.resize(width, height);
		if(scalingShader)
		    mainViewport.update(width, height, false);
		basicViewport.update(width, height, false);
	}
	
	public float flipY(float y){
        return bigHeight  - y;
    }
	public int flipY(int y){
        return bigHeight  - y;
    }
}
// An explanation of hexadecimal float/double literals was mentioned earlier, so here it is.
// The literal 0x1p-9f is a good example; it is essentially the same as writing 0.001953125f,
// (float)Math.pow(2.0, -9.0), or (1f / 512f), but is possibly faster than the last two if the
// compiler can't optimize float division effectively, and is a good tool to have because these
// hexadecimal float or double literals always represent numbers accurately. To contrast,
// 0.3 - 0.2 is not equal to 0.1 with doubles, because tenths are inaccurate with floats and
// doubles, and hex literals won't have the option to write an inaccurate float or double.
// There's some slightly confusing syntax used to write these literals; the 0x means the first
// part uses hex digits (0123456789ABCDEF), but the p is not a hex digit and is used to start
// the "p is for power" exponent section. In the example, I used -9 for the power; this is a
// base 10 number, and is used to mean a power of 2 that the hex digits will be multiplied by.
// Because the -9 is a base 10 number, the f at the end is not a hex digit, and actually just
// means the literal is a float, in the same way 1.5f is a float. 2.0 to the -9 is the same as
// 1.0 / Math.pow(2.0, 9.0), but re-calculating Math.pow() is considerably slower if you run it
// for every cell during every frame. Though this is most useful for negative exponents because
// there are a lot of numbers after the decimal point to write out with 0.001953125 or the like,
// it is also sometimes handy when you have an integer or long written in hexadecimal and want
// to make it a float or double. You could use the hex long 0x9E3779B9L, for instance, but to
// write that as a double you would use 0x9E3779B9p0 , not the invalid syntax 0x9E3779B9.0 .
// We use p0 there because 2 to the 0 is 1, so multiplying by 1 gets us the same hex number.
// Very large numbers can also benefit by using a large positive exponent; using p10 and p+10
// as the last part of a hex literal are equivalent. You can see the hex literal for any given
// float with Float.toHexString(float), or for a double with Double.toHexString(double) .
// SColor provides the packed float versions of all color constants as hex literals in the
// documentation for each SColor.
// More information here: https://blogs.oracle.com/darcy/hexadecimal-floating-point-literals
