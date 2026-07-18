package com.sunlitvalley.menucards.client;

final class HoverSoundTracker {
    private int currentIndex = -1;

    boolean update(int nextIndex) {
        boolean enteredNewCard = nextIndex >= 0 && nextIndex != currentIndex;
        currentIndex = nextIndex;
        return enteredNewCard;
    }
}
