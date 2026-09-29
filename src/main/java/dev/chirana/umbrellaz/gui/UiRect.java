package dev.chirana.umbrellaz.gui;

public record UiRect(int x, int y, int width, int height) {
    public UiRect {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Rectangle dimensions must not be negative");
        }
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public boolean contains(double pointX, double pointY) {
        return pointX >= x && pointX < right() && pointY >= y && pointY < bottom();
    }
}
