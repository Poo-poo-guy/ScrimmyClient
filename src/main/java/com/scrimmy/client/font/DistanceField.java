package com.scrimmy.client.font;

/** Exact squared Euclidean distance transform; CPU work happens only when baking a glyph. */
public final class DistanceField {
    public static final int RANGE = 8, PADDING = RANGE + 3;
    private static final double FAR = 1e12;
    private DistanceField() {}

    public static byte[] encode(byte[] coverage, int width, int height) {
        if (coverage.length != width * height) throw new IllegalArgumentException("Invalid glyph dimensions");
        double[] inside = transform(coverage, width, height, true);
        double[] outside = transform(coverage, width, height, false);
        byte[] result = new byte[coverage.length];
        for (int i = 0; i < result.length; i++) {
            float alpha = (coverage[i] & 255) / 255f;
            double distance = alpha >= .5 ? Math.sqrt(outside[i]) - .5 : .5 - Math.sqrt(inside[i]);
            if (alpha > 0 && alpha < 1) distance = alpha - .5;
            result[i] = (byte)Math.clamp(Math.round(127.5 + distance * 127.5 / RANGE), 0, 255);
        }
        return result;
    }
    private static double[] transform(byte[] coverage, int width, int height, boolean inside) {
        double[] data = new double[coverage.length];
        int size = Math.max(width, height);
        double[] f = new double[size], d = new double[size], boundaries = new double[size + 1];
        int[] sites = new int[size];
        for (int i = 0; i < data.length; i++) data[i] = (((coverage[i] & 255) >= 128) == inside) ? 0 : FAR;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) f[y] = data[y * width + x];
            line(f, d, sites, boundaries, height);
            for (int y = 0; y < height; y++) data[y * width + x] = d[y];
        }
        for (int y = 0; y < height; y++) {
            System.arraycopy(data, y * width, f, 0, width);
            line(f, d, sites, boundaries, width);
            System.arraycopy(d, 0, data, y * width, width);
        }
        return data;
    }
    private static void line(double[] f, double[] d, int[] sites, double[] z, int length) {
        int k = 0;
        sites[0] = 0; z[0] = Double.NEGATIVE_INFINITY; z[1] = Double.POSITIVE_INFINITY;
        for (int q = 1; q < length; q++) {
            double s;
            do {
                int p = sites[k];
                s = ((f[q] + (double)q * q) - (f[p] + (double)p * p)) / (2 * (q - p));
                if (s > z[k] || k == 0) break;
                k--;
            } while (true);
            sites[++k] = q; z[k] = s; z[k + 1] = Double.POSITIVE_INFINITY;
        }
        k = 0;
        for (int q = 0; q < length; q++) {
            while (z[k + 1] < q) k++;
            double delta = q - sites[k]; d[q] = delta * delta + f[sites[k]];
        }
    }
}
