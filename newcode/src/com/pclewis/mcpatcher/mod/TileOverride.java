package com.pclewis.mcpatcher.mod;

import com.pclewis.mcpatcher.MCPatcherUtils;
import net.minecraft.src.Block;
import net.minecraft.src.IBlockAccess;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Properties;

abstract class TileOverride {
    final String filePrefix;
    final String textureName;
    final int texture;
    final int faces;
    final int metadata;
    final boolean connectByTile;
    final int[] tileMap;

    static TileOverride create(String filePrefix, Properties properties, boolean connectByTile) {
        if (filePrefix == null) {
            return null;
        }
        if (properties == null) {
            InputStream is = null;
            try {
                is = CTMUtils.lastTexturePack.getInputStream(filePrefix + ".properties");
                if (is != null) {
                    properties = new Properties();
                    properties.load(is);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                MCPatcherUtils.close(is);
            }
        }
        if (properties == null) {
            return null;
        }

        if (connectByTile) {
            properties.setProperty("connect", "tile");
        }
        String method = properties.getProperty("method", "default").trim().toLowerCase();
        TileOverride override = null;

        if (method.equals("default") || method.equals("glass") || method.equals("ctm")) {
            override = new Default(filePrefix, properties);
        } else if (method.equals("random")) {
            override = new Random1(filePrefix, properties);
        } else if (method.equals("bookshelf") || method.equals("horizontal")) {
            override = new Horizontal(filePrefix, properties);
        } else if (method.equals("sandstone") || method.equals("top")) {
            override = new Top(filePrefix, properties);
        } else if (method.equals("repeat") || method.equals("pattern")) {
            override = new Repeat(filePrefix, properties);
        } else {
            MCPatcherUtils.error("%s.properties: unknown method \"%s\"", filePrefix, method);
        }

        return override != null && override.isValid() ? override : null;
    }

    static TileOverride create(BufferedImage image) {
        TileOverride override = new Default(image);
        return override.isValid() ? override : null;
    }

    private TileOverride(BufferedImage image) {
        filePrefix = null;
        textureName = null;
        texture = MCPatcherUtils.getMinecraft().renderEngine.allocateAndSetupTexture(image);
        faces = -1;
        metadata = -1;
        connectByTile = true;
        tileMap = null;
    }

    private TileOverride(String filePrefix, Properties properties) {
        this.filePrefix = filePrefix;
        textureName = properties.getProperty("source", filePrefix + ".png");
        texture = CTMUtils.getTexture(textureName);
        if (properties.contains("source") && texture < 0) {
            error("source texture %s not found", textureName);
        }

        int flags = 0;
        for (String val : properties.getProperty("faces", "all").trim().toLowerCase().split("\\s+")) {
            if (val.equals("bottom")) {
                flags |= (1 << CTMUtils.BOTTOM_FACE);
            } else if (val.equals("top")) {
                flags |= (1 << CTMUtils.TOP_FACE);
            } else if (val.equals("north")) {
                flags |= (1 << CTMUtils.NORTH_FACE);
            } else if (val.equals("south")) {
                flags |= (1 << CTMUtils.SOUTH_FACE);
            } else if (val.equals("east")) {
                flags |= (1 << CTMUtils.EAST_FACE);
            } else if (val.equals("west")) {
                flags |= (1 << CTMUtils.WEST_FACE);
            } else if (val.equals("side") || val.equals("sides")) {
                flags |= (1 << CTMUtils.NORTH_FACE) | (1 << CTMUtils.SOUTH_FACE) | (1 << CTMUtils.EAST_FACE) | (1 << CTMUtils.WEST_FACE);
            } else if (val.equals("all")) {
                flags = -1;
            }
        }
        faces = flags;

        int meta = 0;
        for (int i : MCPatcherUtils.parseIntegerList(properties.getProperty("metadata", "0-31"))) {
            if (i >= 0 && i < 32) {
                meta |= (1 << i);
            }
        }
        metadata = meta;

        String connectType = properties.getProperty("connect", "tile").trim().toLowerCase();
        connectByTile = connectType.equals("tile");

        String tileList = properties.getProperty("tiles", "");
        int[] defaultTileMap = getDefaultTileMap();
        if (defaultTileMap == null) {
            if (tileList.equals("")) {
                error("no tile map given");
                tileMap = null;
            } else {
                tileMap = MCPatcherUtils.parseIntegerList(tileList);
                if (tileMap.length == 0) {
                    error("no tile map given");
                }
            }
        } else {
            if (tileList.equals("")) {
                tileMap = defaultTileMap;
            } else {
                tileMap = MCPatcherUtils.parseIntegerList(tileList);
                if (tileMap.length != defaultTileMap.length) {
                    error("tile map requires %d entries, got %d", defaultTileMap.length, tileMap.length);
                }
            }
        }
    }

    boolean isValid() {
        return texture >= 0 && tileMap != null && tileMap.length > 0;
    }

    final void error(String format, Object... params) {
        if (filePrefix == null) {
            MCPatcherUtils.error(format, params);
        } else {
            MCPatcherUtils.error(filePrefix + ".properties: " + format, params);
        }
    }

    final boolean shouldConnect(IBlockAccess blockAccess, Block block, int tileNum, int i, int j, int k, int face, int[] offset) {
        i += offset[0];
        j += offset[1];
        k += offset[2];
        if (exclude(blockAccess, block, tileNum, i, j, k, face)) {
            return false;
        } else if (connectByTile) {
            return block.getBlockTexture(blockAccess, i, j, k, face) == tileNum;
        } else {
            return blockAccess.getBlockId(i, j, k) == block.blockID;
        }
    }

    final boolean exclude(IBlockAccess blockAccess, Block block, int origTexture, int i, int j, int k, int face) {
        if ((faces & (1 << face)) == 0) {
            return true;
        } else if (metadata != -1) {
            int meta = blockAccess.getBlockMetadata(i, j, k);
            if (meta >= 0 && meta < 32 && (metadata & (1 << meta)) == 0) {
                return true;
            }
        }
        return false;
    }

    final int getTile(IBlockAccess blockAccess, Block block, int origTexture, int i, int j, int k, int face) {
        if (exclude(blockAccess, block, origTexture, i, j, k, face)) {
            return -1;
        } else {
            return getTileImpl(blockAccess, block, origTexture, i, j, k, face);
        }
    }

    private static int[] compose(int[] map1, int[] map2) {
        int[] newMap = new int[map2.length];
        for (int i = 0; i < map2.length; i++) {
            newMap[i] = map1[map2[i]];
        }
        return newMap;
    }

    abstract int[] getDefaultTileMap();

    abstract int getTileImpl(IBlockAccess blockAccess, Block block, int origTexture, int i, int j, int k, int face);

    static class Default extends TileOverride {
        private static final int[] defaultTileMap = new int[]{
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11,
            16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
            32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43,
            48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59,
        };

        // Index into this array is formed from these bit values:
        // 128 64  32
        // 1   *   16
        // 2   4   8
        private static final int[] neighborMap = new int[]{
            0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15,
            1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
            0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15,
            1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
            36, 17, 36, 17, 24, 19, 24, 43, 36, 17, 36, 17, 24, 19, 24, 43,
            16, 18, 16, 18, 6, 46, 6, 21, 16, 18, 16, 18, 28, 9, 28, 22,
            36, 17, 36, 17, 24, 19, 24, 43, 36, 17, 36, 17, 24, 19, 24, 43,
            37, 40, 37, 40, 30, 8, 30, 34, 37, 40, 37, 40, 25, 23, 25, 45,
            0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15,
            1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
            0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15,
            1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
            36, 39, 36, 39, 24, 41, 24, 27, 36, 39, 36, 39, 24, 41, 24, 27,
            16, 42, 16, 42, 6, 20, 6, 10, 16, 42, 16, 42, 28, 35, 28, 44,
            36, 39, 36, 39, 24, 41, 24, 27, 36, 39, 36, 39, 24, 41, 24, 27,
            37, 38, 37, 38, 30, 11, 30, 32, 37, 38, 37, 38, 25, 33, 25, 26,
        };

        private final int[] neighborTileMap;

        private Default(BufferedImage image) {
            super(image);
            neighborTileMap = compose(defaultTileMap, neighborMap);
        }

        private Default(String filePrefix, Properties properties) {
            super(filePrefix, properties);
            neighborTileMap = compose(tileMap, neighborMap);
        }

        @Override
        int[] getDefaultTileMap() {
            return defaultTileMap;
        }

        @Override
        int getTileImpl(IBlockAccess blockAccess, Block block, int origTexture, int i, int j, int k, int face) {
            int[][] offsets = CTMUtils.NEIGHBOR_OFFSET[face];
            int neighborBits = 0;
            for (int bit = 0; bit < 8; bit++) {
                if (shouldConnect(blockAccess, block, origTexture, i, j, k, face, offsets[bit])) {
                    neighborBits |= (1 << bit);
                }
            }
            return neighborTileMap[neighborBits];
        }
    }

    static class Random1 extends TileOverride {
        private static final long MULTIPLIER = 0x5deece66dL;
        private static final long ADDEND = 0xbL;
        private static final long MASK = (1L << 48) - 1;

        private final boolean allFacesTheSame;
        private final int[] weight;
        private final int sum;

        private Random1(String filePrefix, Properties properties) {
            super(filePrefix, properties);
            allFacesTheSame = Boolean.parseBoolean(properties.getProperty("allFacesTheSame", "false"));
            ArrayList<Integer> w = new ArrayList<Integer>();
            for (String t : properties.getProperty("weights", "").split("\\s+")) {
                if (w.size() >= tileMap.length) {
                    break;
                }
                try {
                    w.add(Math.max(Integer.parseInt(t), 0));
                } catch (NumberFormatException e) {
                }
            }
            while (w.size() < tileMap.length) {
                w.add(1);
            }
            int s = 0;
            boolean useWeight = false;
            for (Integer i : w) {
                s += i;
                if (!i.equals(w.get(0))) {
                    useWeight = true;
                }
            }
            sum = s;
            if (useWeight && sum > 0) {
                weight = new int[tileMap.length];
                for (int i = 0; i < weight.length; i++) {
                    weight[i] = w.get(i);
                }
            } else {
                weight = null;
            }
        }

        @Override
        int[] getDefaultTileMap() {
            return null;
        }

        @Override
        int getTileImpl(IBlockAccess blockAccess, Block block, int origTexture, int i, int j, int k, int face) {
            long n = (allFacesTheSame ? 1 : face);
            n = MULTIPLIER * n * i + ADDEND;
            n = MULTIPLIER * n * j + ADDEND;
            n = MULTIPLIER * n * k + ADDEND;
            n = MULTIPLIER * n + ADDEND;
            n &= MASK;

            double d = (double) n / (double) (MASK + 1);
            if (weight == null) {
                return tileMap[(int) (d * tileMap.length)];
            } else {
                int m = (int) (d * sum);
                int index;
                for (index = 0; index < weight.length - 1 && m >= weight[index]; index++) {
                    m -= weight[index];
                }
                return tileMap[index];
            }
        }

        @Override
        boolean isValid() {
            return super.isValid() && tileMap.length > 0;
        }
    }

    static class Horizontal extends TileOverride {
        private static final int[] defaultTileMap = new int[]{
            12, 13, 14, 15,
        };

        // Index into this array is formed from these bit values:
        // 1   *   2
        private static final int[] neighborMap = new int[]{
            3, 2, 0, 1,
        };

        private final int[] neighborTileMap;

        private Horizontal(String filePrefix, Properties properties) {
            super(filePrefix, properties);
            neighborTileMap = compose(tileMap, neighborMap);
        }

        @Override
        int[] getDefaultTileMap() {
            return defaultTileMap;
        }

        @Override
        int getTileImpl(IBlockAccess blockAccess, Block block, int origTexture, int i, int j, int k, int face) {
            if (face <= CTMUtils.TOP_FACE) {
                return -1;
            }
            int[][] offsets = CTMUtils.NEIGHBOR_OFFSET[face];
            int neighborBits = 0;
            if (shouldConnect(blockAccess, block, origTexture, i, j, k, face, offsets[0])) {
                neighborBits |= 1;
            }
            if (shouldConnect(blockAccess, block, origTexture, i, j, k, face, offsets[4])) {
                neighborBits |= 2;
            }
            return neighborTileMap[neighborBits];
        }
    }

    static class Top extends TileOverride {
        private static final int[] defaultTileMap = new int[]{
            64, 65, 66, 67,
        };

        private Top(String filePrefix, Properties properties) {
            super(filePrefix, properties);
        }

        @Override
        int[] getDefaultTileMap() {
            return defaultTileMap;
        }

        @Override
        int getTileImpl(IBlockAccess blockAccess, Block block, int origTexture, int i, int j, int k, int face) {
            if (face <= CTMUtils.TOP_FACE) {
                return -1;
            }
            if (blockAccess.getBlockMetadata(i, j, k) != 0) {
                return -1;
            }
            if (shouldConnect(blockAccess, block, origTexture, i, j, k, face, CTMUtils.GO_UP)) {
                return tileMap[2];
            }
            return -1;
        }
    }

    static class Repeat extends TileOverride {
        private final int width;
        private final int height;

        Repeat(String filePrefix, Properties properties) {
            super(filePrefix, properties);
            int w = 0;
            int h = 0;
            try {
                w = Integer.parseInt(properties.getProperty("width", "0"));
                h = Integer.parseInt(properties.getProperty("height", "0"));
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
            width = w;
            height = h;
        }

        @Override
        int[] getDefaultTileMap() {
            return null;
        }

        @Override
        boolean isValid() {
            if (!super.isValid()) {
                return false;
            } else if (width <= 0 || height <= 0 || width * height > CTMUtils.NUM_TILES) {
                error("invalid width and height (%dx%d)", width, height);
                return false;
            } else if (tileMap.length != width * height) {
                error("must have exactly width * height (=%d) tiles, got %d", width * height, tileMap.length);
                return false;
            } else {
                return true;
            }
        }

        @Override
        int getTileImpl(IBlockAccess blockAccess, Block block, int origTexture, int i, int j, int k, int face) {
            int x;
            int y;
            switch (face) {
                case CTMUtils.TOP_FACE:
                case CTMUtils.BOTTOM_FACE:
                    x = i;
                    y = k;
                    break;

                case CTMUtils.NORTH_FACE:
                case CTMUtils.SOUTH_FACE:
                    x = i;
                    y = j;
                    break;

                case CTMUtils.EAST_FACE:
                case CTMUtils.WEST_FACE:
                    x = k;
                    y = j;
                    break;

                default:
                    return -1;
            }
            x %= width;
            if (x < 0) {
                x += width;
            }
            y %= height;
            if (y < 0) {
                y += height;
            }
            return tileMap[width * y + x];
        }
    }
}