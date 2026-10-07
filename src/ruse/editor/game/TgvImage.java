package ruse.editor.game;

import ruse.editor.i18n.I18n;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Dekoder für Eugens TGV-Texturen im Format DXT5 bzw. DXT1 (zlib-Block "ZIPO"); liest die größte Mip-Stufe. */
final class TgvImage {

    private TgvImage() {}

    static BufferedImage decode(byte[] d) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int w = b.getInt(8), h = b.getInt(12);
        String fmt = new String(d, 0x1c, 12, StandardCharsets.ISO_8859_1);
        boolean dxt5 = fmt.startsWith("DXT5"), dxt1 = fmt.startsWith("DXT1");
        if (!dxt5 && !dxt1) throw new IOException(I18n.tr("err.tgv.format", fmt.trim()));
        int zipo = GameCatalog.indexOf(d, "ZIPO".getBytes(StandardCharsets.ISO_8859_1));
        if (zipo < 0) throw new IOException(I18n.tr("err.tgv.no_data"));
        int bw = (w + 3) / 4, bh = (h + 3) / 4, blockSize = dxt5 ? 16 : 8;
        byte[] raw = new byte[bw * bh * blockSize];
        Inflater inf = new Inflater();
        inf.setInput(d, zipo + 8, d.length - zipo - 8);
        try {
            int got = 0;
            while (got < raw.length) {
                int r = inf.inflate(raw, got, raw.length - got);
                if (r == 0 && (inf.needsInput() || inf.finished())) break;
                got += r;
            }
            if (got < raw.length) throw new IOException(I18n.tr("err.tgv.incomplete"));
        } catch (DataFormatException e) {
            throw new IOException(I18n.tr("err.tgv.corrupt"), e);
        } finally {
            inf.end();
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteBuffer r = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int p = 0;
        int[] cols = new int[4];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                if (dxt5) p += 8; // Alphablock wird nicht gebraucht
                int c0 = r.getShort(p) & 0xffff, c1 = r.getShort(p + 2) & 0xffff, bits = r.getInt(p + 4);
                p += 8;
                cols[0] = rgb565(c0);
                cols[1] = rgb565(c1);
                if (dxt5 || c0 > c1) {
                    cols[2] = mix(cols[0], cols[1], 2, 1, 3);
                    cols[3] = mix(cols[0], cols[1], 1, 2, 3);
                } else {
                    cols[2] = mix(cols[0], cols[1], 1, 1, 2);
                    cols[3] = 0;
                }
                for (int k = 0; k < 16; k++) {
                    int x = bx * 4 + (k & 3), y = by * 4 + (k >> 2);
                    if (x < w && y < h) img.setRGB(x, y, cols[(bits >>> (2 * k)) & 3]);
                }
            }
        }
        return img;
    }

    private static int rgb565(int c) {
        int r = (c >> 11) * 255 / 31, g = ((c >> 5) & 63) * 255 / 63, b = (c & 31) * 255 / 31;
        return (r << 16) | (g << 8) | b;
    }

    private static int mix(int a, int b, int wa, int wb, int div) {
        int r = (((a >> 16) & 255) * wa + ((b >> 16) & 255) * wb) / div;
        int g = (((a >> 8) & 255) * wa + ((b >> 8) & 255) * wb) / div;
        int bl = ((a & 255) * wa + (b & 255) * wb) / div;
        return (r << 16) | (g << 8) | bl;
    }
}
