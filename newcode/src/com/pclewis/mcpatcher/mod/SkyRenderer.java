package com.pclewis.mcpatcher.mod;

import com.pclewis.mcpatcher.MCPatcherUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.src.*;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Properties;

public class SkyRenderer {
    private static final double DISTANCE = 100.0;

    private static RenderEngine renderEngine;
    private static double worldTime;
    private static float celestialAngle;

    private static final HashMap<Integer, ArrayList<Layer>> worldSkies = new HashMap<Integer, ArrayList<Layer>>();
    private static ArrayList<Layer> currentSkies;
    private static TexturePackBase lastTexturePack;

    public static boolean active;

    public static void setup(World world, RenderEngine renderEngine, float partialTick, float celestialAngle) {
        Minecraft minecraft = MCPatcherUtils.getMinecraft();
        TexturePackBase texturePack = minecraft.texturePackList.getSelectedTexturePack();
        if (texturePack != lastTexturePack) {
            lastTexturePack = texturePack;
            worldSkies.clear();
        }
        if (texturePack instanceof TexturePackDefault || Keyboard.isKeyDown(Keyboard.KEY_ADD)) {
            active = false;
        } else {
            int worldType = minecraft.getWorld().worldProvider.worldType;
            currentSkies = worldSkies.get(worldType);
            if (currentSkies == null) {
                currentSkies = new ArrayList<Layer>();
                worldSkies.put(worldType, currentSkies);
                for (int i = -1; ; i++) {
                    String prefix = "/terrain/sky" + (i < 0 ? "" : "" + worldType) + "/sky" + i;
                    Layer layer = Layer.create(prefix);
                    if (layer == null) {
                        if (i > 0) {
                            break;
                        }
                    } else if (layer.valid) {
                        MCPatcherUtils.info("loaded %s.properties", prefix);
                        currentSkies.add(layer);
                    }
                }
            }
            active = !currentSkies.isEmpty();
            if (active) {
                SkyRenderer.renderEngine = renderEngine;
                worldTime = world.getWorldTime() + partialTick;
                SkyRenderer.celestialAngle = celestialAngle;
            }
        }
    }

    public static void renderAll() {
        if (active) {
            Tessellator tessellator = Tessellator.instance;
            for (Layer layer : currentSkies) {
                layer.render(tessellator, worldTime, celestialAngle);
            }
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        }
    }

    private static void checkGLError() {
        int error = GL11.glGetError();
        if (error != 0) {
            throw new RuntimeException("GL error: " + GLU.gluErrorString(error));
        }
    }

    private static class Layer {
        private static final int SECS_PER_DAY = 24 * 60 * 60;
        private static final int TICKS_PER_DAY = 24000;
        private static final double TOD_OFFSET = -0.25;

        private static final int METHOD_ADD = 1;
        private static final int METHOD_REPLACE = 2;
        private static final int METHOD_MULTIPLY = 3;

        String prefix;
        String texture;
        int startFadeIn;
        int endFadeIn;
        int startFadeOut;
        int endFadeOut;
        boolean rotate;
        int blendMethod;
        boolean valid;

        private double a;
        private double b;
        private double c;

        static Layer create(String prefix) {
            Properties properties = null;
            InputStream is = null;
            try {
                is = lastTexturePack.getInputStream(prefix + ".properties");
                if (is != null) {
                    properties = new Properties();
                    properties.load(is);
                    return new Layer(prefix, properties);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                MCPatcherUtils.close(is);
            }
            return null;
        }

        private Layer(String prefix, Properties properties) {
            this.prefix = prefix;
            valid = true;

            texture = properties.getProperty("source", prefix + ".png");
            if (MCPatcherUtils.readImage(lastTexturePack.getInputStream(texture)) == null) {
                addError("texture %s not found", texture);
                return;
            }

            rotate = Boolean.parseBoolean(properties.getProperty("rotate", "true"));

            String value = properties.getProperty("blend", "add").trim().toLowerCase();
            if (value.equals("add")) {
                blendMethod = METHOD_ADD;
            } else if (value.equals("replace")) {
                blendMethod = METHOD_REPLACE;
            } else if (value.equals("multiply")) {
                blendMethod = METHOD_MULTIPLY;
            } else {
                addError("unknown blend method %s", value);
                return;
            }

            startFadeIn = parseTime(properties, "startFadeIn");
            endFadeIn = parseTime(properties, "endFadeIn");
            endFadeOut = parseTime(properties, "endFadeOut");
            if (!valid) {
                return;
            }
            while (endFadeIn <= startFadeIn) {
                endFadeIn += SECS_PER_DAY;
            }
            while (endFadeOut <= endFadeIn) {
                endFadeOut += SECS_PER_DAY;
            }
            if (endFadeOut - startFadeIn >= SECS_PER_DAY) {
                addError("fade times are incoherent");
                return;
            }
            startFadeOut = startFadeIn + endFadeOut - endFadeIn;

            double s0 = normalize(startFadeIn, SECS_PER_DAY, TOD_OFFSET);
            double s1 = normalize(endFadeIn, SECS_PER_DAY, TOD_OFFSET);
            double e0 = normalize(startFadeOut, SECS_PER_DAY, TOD_OFFSET);
            double e1 = normalize(endFadeOut, SECS_PER_DAY, TOD_OFFSET);
            double det = Math.cos(s0) * Math.sin(s1) + Math.cos(e1) * Math.sin(s0) + Math.cos(s1) * Math.sin(e1) -
                Math.cos(s0) * Math.sin(e1) - Math.cos(s1) * Math.sin(s0) - Math.cos(e1) * Math.sin(s1);
            if (det == 0.0) {
                addError("determinant is 0");
                return;
            }
            a = (Math.sin(e1) - Math.sin(s0)) / det;
            b = (Math.cos(s0) - Math.cos(e1)) / det;
            c = (Math.cos(e1) * Math.sin(s0) - Math.cos(s0) * Math.sin(e1)) / det;

            MCPatcherUtils.info("%s.properties: y = %f cos x + %f sin x + %f", prefix, a, b, c);
            MCPatcherUtils.info("  at %f: %f", s0, a * Math.cos(s0) + b * Math.sin(s0) + c);
            MCPatcherUtils.info("  at %f: %f", s1, a * Math.cos(s1) + b * Math.sin(s1) + c);
            MCPatcherUtils.info("  at %f: %f", e0, a * Math.cos(e0) + b * Math.sin(e0) + c);
            MCPatcherUtils.info("  at %f: %f", e1, a * Math.cos(e1) + b * Math.sin(e1) + c);
        }

        private void addError(String format, Object... params) {
            MCPatcherUtils.error(prefix + ".properties: " + format, params);
            valid = false;
        }

        private int parseTime(Properties properties, String key) {
            String s = properties.getProperty(key, "").trim();
            if ("".equals(s)) {
                addError("missing value for %s", key);
                return -1;
            }
            String[] t = s.split(":");
            if (t.length >= 2) {
                try {
                    int hh = Integer.parseInt(t[0].trim());
                    int mm = Integer.parseInt(t[1].trim());
                    int ss;
                    if (t.length >= 3) {
                        ss = Integer.parseInt(t[2].trim());
                    } else {
                        ss = 0;
                    }
                    return (60 * 60 * hh + 60 * mm + ss) % SECS_PER_DAY;
                } catch (NumberFormatException e) {
                }
            }
            addError("invalid %s time %s", key, s);
            return -1;
        }

        private static double normalize(double time, int period, double offset) {
            return 2.0 * Math.PI * (time / period + offset);
        }

        boolean render(Tessellator tessellator, double worldTime, float celestialAngle) {
            double x = normalize(worldTime, TICKS_PER_DAY, 0.0);
            float brightness = (float) (a * Math.cos(x) + b * Math.sin(x) + c);
            if (brightness <= 0.0f) {
                return false;
            }
            if (brightness > 1.0f) {
                brightness = 1.0f;
            }

            setBlendingMethod();
            GL11.glColor4f(1.0f, 1.0f, 1.0f, brightness);

            renderEngine.bindTexture(renderEngine.getTexture(texture));

            GL11.glPushMatrix();

            if (rotate) {
                GL11.glRotatef(celestialAngle * 360.0f, 1.0f, 0.0f, 0.0f);
            }

            // north
            GL11.glRotatef(90.0f, 1.0f, 0.0f, 0.0f);
            GL11.glRotatef(-90.0f, 0.0f, 0.0f, 1.0f);
            drawTile(tessellator, 5);

            // top
            GL11.glPushMatrix();
            GL11.glRotatef(90.0f, 1.0f, 0.0f, 0.0f);
            drawTile(tessellator, 1);
            GL11.glPopMatrix();

            // bottom
            GL11.glPushMatrix();
            GL11.glRotatef(-90.0f, 1.0f, 0.0f, 0.0f);
            drawTile(tessellator, 9);
            GL11.glPopMatrix();

            // west
            GL11.glRotatef(90.0f, 0.0f, 0.0f, 1.0f);
            drawTile(tessellator, 6);

            // south
            GL11.glRotatef(90.0f, 0.0f, 0.0f, 1.0f);
            drawTile(tessellator, 7);

            // east
            GL11.glRotatef(90.0f, 0.0f, 0.0f, 1.0f);
            drawTile(tessellator, 4);

            GL11.glPopMatrix();

            resetBlendingMethod();
            return true;
        }

        private static void drawTile(Tessellator tessellator, int tile) {
            double tileX = (tile % 4) / 4.0;
            double tileY = (tile / 4) / 3.0;
            tessellator.startDrawingQuads();
            tessellator.addVertexWithUV(-DISTANCE, -DISTANCE, -DISTANCE, tileX, tileY);
            tessellator.addVertexWithUV(-DISTANCE, -DISTANCE, DISTANCE, tileX, tileY + 1.0 / 3.0);
            tessellator.addVertexWithUV(DISTANCE, -DISTANCE, DISTANCE, tileX + 0.25, tileY + 1.0 / 3.0);
            tessellator.addVertexWithUV(DISTANCE, -DISTANCE, -DISTANCE, tileX + 0.25, tileY);
            tessellator.draw();
        }

        private void setBlendingMethod() {
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_ALPHA_TEST);

            switch (blendMethod) {
                case METHOD_ADD:
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                    break;

                case METHOD_REPLACE:
                    GL11.glDisable(GL11.GL_BLEND);
                    break;

                case METHOD_MULTIPLY:
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_ZERO, GL11.GL_SRC_COLOR);
                    break;

                default:
                    break;
            }

            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }

        private void resetBlendingMethod() {
            GL11.glEnable(GL11.GL_ALPHA_TEST);
        }
    }
}