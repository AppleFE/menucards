package com.sunlitvalley.menucards.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.sunlitvalley.menucards.network.CardActionC2SPacket;
import com.sunlitvalley.menucards.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class MenuScreen extends Screen {

    private static final int TEX_W = 320;
    private static final int TEX_H = 480;
    private static final double STAGGER = 0.06;
    private static final double DURATION = 0.30;

    private long openNanos;
    private long lastRenderNanos;

    private int cardW;
    private int cardH;
    private int gap;
    private int totalRowWidth;

    private final HoverState[] hoverStates;
    private final List<Rect> currentBounds = new ArrayList<>();
    private double[] currentAlphas = new double[0];
    private boolean initialized = false;

    private int hoveredIndex = -1;

    public MenuScreen() {
        super(Component.translatable("key.menucards.open"));
        MenuCard[] cards = MenuCard.values();
        hoverStates = new HoverState[cards.length];
        for (int i = 0; i < cards.length; i++) {
            hoverStates[i] = new HoverState();
        }
    }

    @Override
    protected void init() {
        super.init();
        if (!initialized) {
            openNanos = System.nanoTime();
            lastRenderNanos = openNanos;
            initialized = true;
        }

        cardH = (int) Math.min(160, this.height * 0.32);
        cardW = cardH * 2 / 3;
        gap = (int) (cardW * 0.3);
        MenuCard[] cards = MenuCard.values();
        totalRowWidth = cards.length * cardW + (cards.length - 1) * gap;
    }

    private double easeOutBack(double t) {
        double c1 = 1.70158;
        double c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Dark background overlay
        guiGraphics.fill(0, 0, this.width, this.height, 0x88000000);

        long now = System.nanoTime();
        double elapsed = (now - openNanos) / 1e9;
        double dt = Math.min(0.1, Math.max(0, (now - lastRenderNanos) / 1e9));
        lastRenderNanos = now;

        MenuCard[] cards = MenuCard.values();
        int startX = (this.width - totalRowWidth) / 2;
        int centerY = this.height / 2;

        // Compute hovered index first
        hoveredIndex = -1;
        // We'll fill currentBounds during render and use them for hit-testing
        currentBounds.clear();

        // First pass: compute all bounds and hover state
        Rect[] bounds = new Rect[cards.length];
        double[] finalScales = new double[cards.length];
        double[] alphas = new double[cards.length];
        double[] offsetsY = new double[cards.length];

        for (int i = 0; i < cards.length; i++) {
            double local = elapsed - i * STAGGER;
            double p = Math.max(0, Math.min(1, local / DURATION));
            double eased = easeOutBack(p);

            double entranceScale = 0.80 + 0.20 * eased;
            offsetsY[i] = 40.0 * (1 - eased);
            alphas[i] = Math.max(0, Math.min(1, p));
        }
        currentAlphas = alphas;


        // Preliminary hover check using last frame's bounds
        for (int i = 0; i < cards.length; i++) {
            double local = elapsed - i * STAGGER;
            double p = Math.max(0, Math.min(1, local / DURATION));
            double eased = easeOutBack(p);
            double entranceScale = 0.80 + 0.20 * eased;
            double hoverScale = hoverStates[i].getCurrentScale();
            double finalScale = entranceScale * hoverScale;
            finalScales[i] = finalScale;

            int cardCenterX = startX + i * (cardW + gap) + cardW / 2;
            int drawW = (int) (cardW * finalScale);
            int drawH = (int) (cardH * finalScale);
            int drawCenterY = centerY + (int) offsetsY[i];

            bounds[i] = new Rect(cardCenterX - drawW / 2, drawCenterY - drawH / 2, drawW, drawH);
        }

        // Determine hovered (iterate reverse for topmost-wins)
        hoveredIndex = -1;
        for (int i = cards.length - 1; i >= 0; i--) {
            if (bounds[i].contains(mouseX, mouseY)) {
                hoveredIndex = i;
                break;
            }
        }

        // Update hover states with correct hovered index
        for (int i = 0; i < cards.length; i++) {
            hoverStates[i].update(i == hoveredIndex, dt);
            // Recompute final scale with updated hover
            double local = elapsed - i * STAGGER;
            double p = Math.max(0, Math.min(1, local / DURATION));
            double eased = easeOutBack(p);
            double entranceScale = 0.80 + 0.20 * eased;
            double finalScale = entranceScale * hoverStates[i].getCurrentScale();
            finalScales[i] = finalScale;

            int cardCenterX = startX + i * (cardW + gap) + cardW / 2;
            int drawW = (int) (cardW * finalScale);
            int drawH = (int) (cardH * finalScale);
            int drawCenterY = centerY + (int) offsetsY[i];
            bounds[i] = new Rect(cardCenterX - drawW / 2, drawCenterY - drawH / 2, drawW, drawH);
            currentBounds.add(bounds[i]);
        }

        // Render: non-hovered first, hovered last (z-order)
        List<Integer> renderOrder = new ArrayList<>();
        for (int i = 0; i < cards.length; i++) {
            if (i != hoveredIndex) renderOrder.add(i);
        }
        if (hoveredIndex >= 0) renderOrder.add(hoveredIndex);

        for (int idx : renderOrder) {
            MenuCard card = cards[idx];
            Rect r = bounds[idx];
            float a = (float) alphas[idx];
            double scale = finalScales[idx];

            double baseScaleX = (double) r.w / TEX_W;
            double baseScaleY = (double) r.h / TEX_H;

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(r.x, r.y, 0);
            guiGraphics.pose().scale((float) baseScaleX, (float) baseScaleY, 1);

            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1f, 1f, 1f, a);
            guiGraphics.blit(card.getTexture(), 0, 0, 0f, 0f, TEX_W, TEX_H, TEX_W, TEX_H);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();

            guiGraphics.pose().popPose();
        }

        // Render lore tooltip for hovered card
        if (hoveredIndex >= 0 && alphas[hoveredIndex] > 0.5) {
            MenuCard card = cards[hoveredIndex];
            Component lore = Component.translatable(card.getLoreKey());
            Rect r = bounds[hoveredIndex];
            int tooltipX = r.x + r.w / 2;
            int tooltipY = r.y + r.h + 8;
            guiGraphics.renderTooltip(this.font, lore, tooltipX, tooltipY);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !currentBounds.isEmpty()) {
            for (int i = currentBounds.size() - 1; i >= 0; i--) {
                if (currentBounds.get(i).contains((int) mouseX, (int) mouseY)
                        && i < currentAlphas.length && currentAlphas[i] > 0.5) {
                    MenuCard card = MenuCard.values()[i];
                    ModNetwork.sendToServer(new CardActionC2SPacket(card.getId()));
                    this.onClose();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static class Rect {
        final int x, y, w, h;
        Rect(int x, int y, int w, int h) {
            this.x = x; this.y = y; this.w = w; this.h = h;
        }
        boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }
}
