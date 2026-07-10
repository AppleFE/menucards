package com.sunlitvalley.menucards.client;

public class HoverState {
    private double currentScale = 1.0;

    public void update(boolean hovered, double dt) {
        double target = hovered ? 1.15 : 1.0;
        currentScale += (target - currentScale) * Math.min(1.0, dt * 12.0);
    }

    public double getCurrentScale() {
        return currentScale;
    }
}
