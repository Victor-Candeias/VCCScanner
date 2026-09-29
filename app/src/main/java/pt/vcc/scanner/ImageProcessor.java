package pt.vcc.scanner;

import android.graphics.*;

/**
 * Automatic page processing: contour detection, perspective correction, shadow removal and contrast
 * enhancement. Everything works on plain bitmaps and allocates only row buffers, so the callers can
 * run it on a background thread without holding a second copy of the page in memory.
 */
public final class ImageProcessor {
    private ImageProcessor() {}

    /** Longest side used while looking for the page outline: enough detail and cheap to scan. */
    private static final int DETECT_SIZE = 420;
    /** Fractions of the Otsu threshold retried when the first segmentation is not a usable page. */
    private static final float[] THRESHOLD_STEPS = {1f, .85f, .7f};
    /** A quadrilateral this close to the border means the image is already only the page. */
    private static final float FULL_FRAME_MARGIN = .02f;
    /** Illumination is considered uneven above this difference between the brightest and darkest paper. */
    private static final int UNEVEN_LIGHT = 26;
    /** Paper darker than this on average comes from an underexposed photo and is worth correcting. */
    private static final int DARK_PAPER = 170;
    /** Never divide by a darker level than this: it would only amplify noise. */
    private static final int MIN_PAPER = 48;

    /**
     * Deskews and cleans a freshly imported page. Returns null when the image already looks like a
     * flat, evenly lit scan and nothing has to be corrected.
     */
    public static Bitmap process(Bitmap source) {
        Bitmap deskewed = null;
        float[] corners = detect(source);
        if (corners != null) deskewed = warp(source, corners);
        Bitmap base = deskewed != null ? deskewed : source;
        Bitmap cleaned = needsEnhancement(base) ? enhance(base) : null;
        if (cleaned != null && deskewed != null) deskewed.recycle();
        return cleaned != null ? cleaned : deskewed;
    }

    /**
     * Looks for the outline of a document photographed over a darker surface and returns its four
     * corners clockwise from the top left, in normalized image coordinates. Returns null when no
     * convincing quadrilateral is found or when the page already fills the frame.
     */
    public static float[] detect(Bitmap source) {
        int width = source.getWidth(), height = source.getHeight();
        if (width < 40 || height < 40) return null;
        float scale = Math.min(1f, (float) DETECT_SIZE / Math.max(width, height));
        int w = Math.max(16, Math.round(width * scale)), h = Math.max(16, Math.round(height * scale));
        int[] luminance = new int[w * h];
        Bitmap small = Bitmap.createScaledBitmap(source, w, h, true);
        try {
            int[] pixels = new int[w * h];
            small.getPixels(pixels, 0, w, 0, 0, w, h);
            for (int i = 0; i < pixels.length; i++) luminance[i] = luminance(pixels[i]);
        } finally { if (small != source) small.recycle(); }
        int[] histogram = new int[256];
        for (int value : luminance) histogram[value]++;
        int otsu = otsu(histogram, luminance.length);
        for (float step : THRESHOLD_STEPS) {
            float[] corners = quadrilateral(luminance, w, h, Math.round(otsu * step));
            if (corners != null) return corners;
        }
        return null;
    }

    /**
     * Maps the given corners, clockwise from the top left in normalized coordinates, onto a
     * rectangle the size of the longest pair of opposite sides. Returns null when the corners do not
     * describe a mappable quadrilateral.
     */
    public static Bitmap warp(Bitmap source, float[] corners) {
        float[] src = corners.clone();
        for (int i = 0; i < 4; i++) { src[i * 2] *= source.getWidth(); src[i * 2 + 1] *= source.getHeight(); }
        int w = Math.max(1, Math.round(Math.max(distance(src, 0, 1), distance(src, 3, 2))));
        int h = Math.max(1, Math.round(Math.max(distance(src, 0, 3), distance(src, 1, 2))));
        float[] dest = {0, 0, w, 0, w, h, 0, h};
        Matrix matrix = new Matrix();
        if (!matrix.setPolyToPoly(src, 0, dest, 0, 4)) return null;
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.eraseColor(Color.WHITE);
        new Canvas(result).drawBitmap(source, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    /** True when the page is lit unevenly or is too dark, the two cases {@link #enhance} corrects. */
    public static boolean needsEnhancement(Bitmap source) {
        Illumination light = illumination(source);
        return light.range() > UNEVEN_LIGHT || light.average() < DARK_PAPER;
    }

    /**
     * Removes shadows and gradients by dividing every pixel by the paper level estimated around it,
     * then stretches the result so the ink is black and the paper is white. Colour is preserved: the
     * correction is computed on the luminance and applied to the three channels as a common gain.
     */
    public static Bitmap enhance(Bitmap source) {
        int w = source.getWidth(), h = source.getHeight();
        Illumination light = illumination(source);
        int[] row = new int[w];
        // Sampled histogram of the corrected luminance: enough to place the black and white points.
        int[] corrected = new int[256];
        for (int y = 0; y < h; y += 3) {
            source.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x += 3) corrected[normalize(luminance(row[x]), light.at(x, y))]++;
        }
        int low = percentile(corrected, .02f), high = percentile(corrected, .995f);
        if (high - low < 40) { low = 0; high = 255; }
        int[] stretch = new int[256];
        for (int value = 0; value < 256; value++) stretch[value] = clamp(Math.round((value - low) * 255f / (high - low)));
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++) {
            source.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int pixel = row[x], value = luminance(pixel), target = stretch[normalize(value, light.at(x, y))];
                if (value <= 0) { row[x] = (pixel & 0xff000000) | (target << 16) | (target << 8) | target; continue; }
                float gain = target / (float) value;
                row[x] = (pixel & 0xff000000)
                        | (clamp(Math.round(((pixel >> 16) & 255) * gain)) << 16)
                        | (clamp(Math.round(((pixel >> 8) & 255) * gain)) << 8)
                        | clamp(Math.round((pixel & 255) * gain));
            }
            result.setPixels(row, 0, w, 0, y, w, 1);
        }
        return result;
    }

    /** Paper level of every cell of a coarse grid, the local white the correction divides by. */
    private static final class Illumination {
        final int cell, columns, rows;
        final int[] level;
        Illumination(int cell, int columns, int rows, int[] level) { this.cell = cell; this.columns = columns; this.rows = rows; this.level = level; }
        /** Paper level at a pixel, interpolated between the surrounding cell centres. */
        int at(int x, int y) {
            float gx = x / (float) cell - .5f, gy = y / (float) cell - .5f;
            int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy);
            float fx = gx - x0, fy = gy - y0;
            int x1 = bound(x0 + 1, columns), y1 = bound(y0 + 1, rows);
            x0 = bound(x0, columns); y0 = bound(y0, rows);
            float top = level[y0 * columns + x0] * (1 - fx) + level[y0 * columns + x1] * fx;
            float bottom = level[y1 * columns + x0] * (1 - fx) + level[y1 * columns + x1] * fx;
            return Math.max(MIN_PAPER, Math.round(top * (1 - fy) + bottom * fy));
        }
        int range() {
            int min = 255, max = 0;
            for (int value : level) { min = Math.min(min, value); max = Math.max(max, value); }
            return max - min;
        }
        int average() { long sum = 0; for (int value : level) sum += value; return (int) (sum / level.length); }
        private static int bound(int value, int size) { return Math.max(0, Math.min(size - 1, value)); }
    }

    private static Illumination illumination(Bitmap source) {
        int w = source.getWidth(), h = source.getHeight();
        int cell = Math.max(16, Math.min(w, h) / 24);
        int columns = (w + cell - 1) / cell, rows = (h + cell - 1) / cell;
        int[][] histograms = new int[columns * rows][256];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            source.getPixels(row, 0, w, 0, y, w, 1);
            int band = (y / cell) * columns;
            for (int x = 0; x < w; x++) histograms[band + x / cell][luminance(row[x])]++;
        }
        int[] raw = new int[columns * rows];
        // A high percentile inside the cell is the paper: the ink only covers part of it.
        for (int i = 0; i < raw.length; i++) raw[i] = percentile(histograms[i], .82f);
        int[] level = new int[raw.length];
        for (int y = 0; y < rows; y++) for (int x = 0; x < columns; x++) {
            int sum = 0, count = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= columns || ny >= rows) continue;
                sum += raw[ny * columns + nx]; count++;
            }
            level[y * columns + x] = sum / count;
        }
        return new Illumination(cell, columns, rows, level);
    }

    /**
     * Largest bright region above the threshold, reduced to the quadrilateral formed by its extreme
     * points, or null when that region does not look like a page seen at an angle.
     */
    private static float[] quadrilateral(int[] luminance, int w, int h, int threshold) {
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        int bestArea = 0, bestMinX = 0, bestMinY = 0, bestMaxX = 0, bestMaxY = 0;
        float[] best = null;
        for (int start = 0; start < luminance.length; start++) {
            if (seen[start] || luminance[start] < threshold) continue;
            int top = 0; stack[top++] = start; seen[start] = true;
            int area = 0, minSum = Integer.MAX_VALUE, maxSum = Integer.MIN_VALUE, minDiff = Integer.MAX_VALUE, maxDiff = Integer.MIN_VALUE;
            int minX = w, minY = h, maxX = -1, maxY = -1;
            float[] corners = new float[8];
            while (top > 0) {
                int index = stack[--top], x = index % w, y = index / w;
                area++;
                int sum = x + y, diff = x - y;
                if (sum < minSum) { minSum = sum; corners[0] = x; corners[1] = y; }
                if (diff > maxDiff) { maxDiff = diff; corners[2] = x; corners[3] = y; }
                if (sum > maxSum) { maxSum = sum; corners[4] = x; corners[5] = y; }
                if (diff < minDiff) { minDiff = diff; corners[6] = x; corners[7] = y; }
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (x > 0 && !seen[index - 1] && luminance[index - 1] >= threshold) { seen[index - 1] = true; stack[top++] = index - 1; }
                if (x < w - 1 && !seen[index + 1] && luminance[index + 1] >= threshold) { seen[index + 1] = true; stack[top++] = index + 1; }
                if (y > 0 && !seen[index - w] && luminance[index - w] >= threshold) { seen[index - w] = true; stack[top++] = index - w; }
                if (y < h - 1 && !seen[index + w] && luminance[index + w] >= threshold) { seen[index + w] = true; stack[top++] = index + w; }
            }
            if (area > bestArea) { bestArea = area; best = corners; bestMinX = minX; bestMinY = minY; bestMaxX = maxX; bestMaxY = maxY; }
        }
        if (best == null) return null;
        float imageArea = (float) w * h;
        if (bestArea < imageArea * .15f || bestArea > imageArea * .995f) return null;
        // A page is whatever the camera was pointed at, so it has to cover the centre of the frame.
        if (bestMinX > w / 2 || bestMaxX < w / 2 || bestMinY > h / 2 || bestMaxY < h / 2) return null;
        if (!convex(best)) return null;
        float quadArea = area(best);
        if (quadArea <= 0 || bestArea < quadArea * .78f) return null;
        float diagonal = (float) Math.hypot(w, h);
        for (int i = 0; i < 4; i++) if (distance(best, i, (i + 1) % 4) < diagonal * .18f) return null;
        if (fullFrame(best, w, h)) return null;
        float[] normalized = new float[8];
        for (int i = 0; i < 4; i++) {
            normalized[i * 2] = Math.max(0, Math.min(1, best[i * 2] / (w - 1f)));
            normalized[i * 2 + 1] = Math.max(0, Math.min(1, best[i * 2 + 1] / (h - 1f)));
        }
        return normalized;
    }

    /** True when the corners turn consistently clockwise and every angle is close enough to square. */
    private static boolean convex(float[] corners) {
        for (int i = 0; i < 4; i++) {
            int a = i * 2, b = ((i + 1) % 4) * 2, c = ((i + 2) % 4) * 2;
            float ux = corners[b] - corners[a], uy = corners[b + 1] - corners[a + 1];
            float vx = corners[c] - corners[b], vy = corners[c + 1] - corners[b + 1];
            if (ux * vy - uy * vx <= 0) return false;
            double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, -(ux * vx + uy * vy) / (Math.hypot(ux, uy) * Math.hypot(vx, vy))))));
            if (angle < 55 || angle > 125) return false;
        }
        return true;
    }

    private static boolean fullFrame(float[] corners, int w, int h) {
        for (int i = 0; i < 4; i++) {
            float x = corners[i * 2] / (w - 1f), y = corners[i * 2 + 1] / (h - 1f);
            float targetX = (i == 0 || i == 3) ? 0 : 1, targetY = i < 2 ? 0 : 1;
            if (Math.abs(x - targetX) > FULL_FRAME_MARGIN || Math.abs(y - targetY) > FULL_FRAME_MARGIN) return false;
        }
        return true;
    }

    private static float area(float[] corners) {
        float total = 0;
        for (int i = 0; i < 4; i++) {
            int a = i * 2, b = ((i + 1) % 4) * 2;
            total += corners[a] * corners[b + 1] - corners[b] * corners[a + 1];
        }
        return total / 2;
    }

    private static int otsu(int[] histogram, int total) {
        long sum = 0;
        for (int value = 0; value < 256; value++) sum += (long) value * histogram[value];
        long belowSum = 0;
        int below = 0, best = 128;
        double bestVariance = -1;
        for (int value = 0; value < 256; value++) {
            below += histogram[value];
            if (below == 0) continue;
            int above = total - below;
            if (above == 0) break;
            belowSum += (long) value * histogram[value];
            double mean = belowSum / (double) below - (sum - belowSum) / (double) above;
            double variance = (double) below * above * mean * mean;
            if (variance > bestVariance) { bestVariance = variance; best = value; }
        }
        return best;
    }

    private static int percentile(int[] histogram, float quantile) {
        int total = 0;
        for (int count : histogram) total += count;
        if (total == 0) return 255;
        int target = Math.max(1, Math.round(total * quantile)), seen = 0;
        for (int value = 0; value < 256; value++) { seen += histogram[value]; if (seen >= target) return value; }
        return 255;
    }

    private static int normalize(int value, int paper) { return Math.min(255, value * 255 / paper); }
    private static int luminance(int pixel) { return (((pixel >> 16) & 255) * 77 + ((pixel >> 8) & 255) * 151 + (pixel & 255) * 28) >> 8; }
    private static int clamp(int value) { return value < 0 ? 0 : Math.min(value, 255); }
    private static float distance(float[] points, int a, int b) { return (float) Math.hypot(points[a * 2] - points[b * 2], points[a * 2 + 1] - points[b * 2 + 1]); }
}
