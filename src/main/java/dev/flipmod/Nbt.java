package dev.flipmod;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Minimal NBT reader (no Minecraft classes) for Hypixel's gzip+base64 item_bytes. */
public final class Nbt {
    private Nbt() {}

    /** Reads a gzip-compressed NBT blob and returns the root compound as a Map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readGzip(byte[] gz) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(gz)))) {
            int type = in.readByte();
            if (type != 10) throw new IOException("root is not a compound");
            in.readUTF(); // root name
            return (Map<String, Object>) payload(in, 10, 0);
        }
    }

    private static Object payload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("nbt too deep");
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                int n = in.readInt();
                if (n < 0 || n > (1 << 24)) throw new IOException("bad array size");
                byte[] b = new byte[n];
                in.readFully(b);
                return b;
            }
            case 8: return in.readUTF();
            case 9: {
                int t = in.readByte();
                int n = in.readInt();
                if (n < 0 || n > (1 << 20)) throw new IOException("bad list size");
                if (t == 0 && n > 0) throw new IOException("bad list type");
                List<Object> l = new ArrayList<>(Math.min(n, 1024));
                for (int i = 0; i < n; i++) l.add(payload(in, t, depth + 1));
                return l;
            }
            case 10: {
                Map<String, Object> m = new HashMap<>();
                while (true) {
                    int t = in.readByte();
                    if (t == 0) break;
                    String name = in.readUTF();
                    m.put(name, payload(in, t, depth + 1));
                }
                return m;
            }
            case 11: {
                int n = in.readInt();
                if (n < 0 || n > (1 << 22)) throw new IOException("bad array size");
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = in.readInt();
                return a;
            }
            case 12: {
                int n = in.readInt();
                if (n < 0 || n > (1 << 21)) throw new IOException("bad array size");
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = in.readLong();
                return a;
            }
            default: throw new IOException("unknown tag " + type);
        }
    }
}
