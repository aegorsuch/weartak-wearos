import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

/** Normalize only supported Unix ZIP mode fields; the caller must verify the pinned AAR hash. */
public class NormalizeSdkAarModes {
    private static final int MAX_ARCHIVE_BYTES = 32 * 1024 * 1024;
    private static final long MAX_CONTENT_BYTES = 64L * 1024 * 1024;
    private static final int FILE_600 = 0100600;
    private static final int FILE_644 = 0100644;
    private static final int DIR_755 = 0040755;

    private record Range(int start, int end) {}

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return (u16(data, offset) | ((long) u16(data, offset + 2) << 16));
    }

    private static void require(boolean valid, String message) throws IOException {
        if (!valid) throw new IOException("Unsupported SDK AAR: " + message);
    }

    private static void bounds(byte[] data, long offset, long size) throws IOException {
        require(offset >= 0 && size >= 0 && offset <= data.length && size <= data.length - offset,
                "record outside archive");
    }

    private static String safeName(byte[] data, int offset, int length) throws IOException {
        require(length > 0, "empty entry name");
        for (int i = offset; i < offset + length; i++) {
            int c = data[i] & 255;
            require(c >= 32 && c < 127 && c != '\\', "non-ASCII or unsafe name");
        }
        String name = new String(data, offset, length, StandardCharsets.US_ASCII);
        require(!name.startsWith("/") && !name.contains("//"), "unsafe path");
        for (String part : name.split("/")) {
            require(!part.equals(".") && !part.equals("..") && !part.contains(":"), "unsafe path");
        }
        return name;
    }

    private static void verifyContents(Path path, Set<String> names) throws IOException {
        long total = 0;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entries = zip.entries();
            int count = 0;
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                require(names.contains(entry.getName()), "central directory mismatch");
                CRC32 crc = new CRC32();
                long length = 0;
                try (var input = zip.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        length += read;
                        total += read;
                        require(total <= MAX_CONTENT_BYTES, "uncompressed content too large");
                        crc.update(buffer, 0, read);
                    }
                }
                require(length == entry.getSize() && crc.getValue() == entry.getCrc(),
                        "entry size or CRC mismatch");
                count++;
            }
            require(count == names.size(), "entry count mismatch");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected SDK AAR path");
        Path path = Path.of(args[0]);
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "not a regular file");
        long size = Files.size(path);
        require(size >= 22 && size <= MAX_ARCHIVE_BYTES, "archive size");
        byte[] data = Files.readAllBytes(path);
        require(data.length == size, "archive changed while reading");

        int end = data.length - 22;
        require(u32(data, end) == 0x06054b50L && u16(data, end + 20) == 0,
                "EOCD, trailing bytes, or archive comment");
        require(u16(data, end + 4) == 0 && u16(data, end + 6) == 0, "multi-disk archive");
        int count = u16(data, end + 10);
        require(count > 0 && count != 0xffff && count == u16(data, end + 8), "entry count");
        long centralSize = u32(data, end + 12);
        long centralOffset = u32(data, end + 16);
        require(centralSize != 0xffffffffL && centralOffset != 0xffffffffL
                && centralOffset + centralSize == end, "central directory or ZIP64");
        bounds(data, centralOffset, centralSize);
        int cursor = (int) centralOffset;
        List<Range> localRanges = new ArrayList<>();
        Set<String> names = new HashSet<>();
        List<Integer> modesToFix = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            bounds(data, cursor, 46);
            require(u32(data, cursor) == 0x02014b50L, "central entry signature");
            require((u16(data, cursor + 4) >>> 8) == 3, "non-Unix entry");
            int flags = u16(data, cursor + 8);
            int method = u16(data, cursor + 10);
            require((flags & ~0x0800) == 0 && method == 8, "flags or compression method");
            long compressed = u32(data, cursor + 20);
            long uncompressed = u32(data, cursor + 24);
            int nameLength = u16(data, cursor + 28);
            int extraLength = u16(data, cursor + 30);
            int commentLength = u16(data, cursor + 32);
            require(compressed != 0xffffffffL && uncompressed != 0xffffffffL
                    && extraLength == 0 && commentLength == 0 && u16(data, cursor + 34) == 0,
                    "ZIP64, extras, comment, or multi-disk entry");
            bounds(data, cursor + 46L, nameLength);
            String name = safeName(data, cursor + 46, nameLength);
            require(names.add(name), "duplicate name");
            int mode = u16(data, cursor + 40);
            int lowAttributes = u16(data, cursor + 38);
            if (name.endsWith("/")) {
                require(mode == DIR_755 && lowAttributes == 0x10 && uncompressed == 0,
                        "directory attributes");
            } else {
                require((mode == FILE_600 || mode == FILE_644) && lowAttributes == 0,
                        "file attributes");
                if (mode == FILE_600) modesToFix.add(cursor + 40);
            }
            long local = u32(data, cursor + 42);
            bounds(data, local, 30);
            int localOffset = (int) local;
            require(u32(data, localOffset) == 0x04034b50L
                    && u16(data, localOffset + 6) == flags
                    && u16(data, localOffset + 8) == method
                    && u32(data, localOffset + 14) == u32(data, cursor + 16)
                    && u32(data, localOffset + 18) == compressed
                    && u32(data, localOffset + 22) == uncompressed
                    && u16(data, localOffset + 26) == nameLength
                    && u16(data, localOffset + 28) == 0, "local record mismatch");
            bounds(data, local + 30, nameLength + compressed);
            require(Arrays.equals(data, localOffset + 30, localOffset + 30 + nameLength,
                    data, cursor + 46, cursor + 46 + nameLength), "local name mismatch");
            long localEnd = local + 30 + nameLength + compressed;
            require(localEnd <= centralOffset, "local record overlaps central directory");
            localRanges.add(new Range(localOffset, (int) localEnd));
            cursor += 46 + nameLength;
        }
        require(cursor == end, "central directory length");
        localRanges.sort(Comparator.comparingInt(Range::start));
        int next = 0;
        for (Range range : localRanges) {
            require(range.start() == next, "overlapping or gapped local records");
            next = range.end();
        }
        require(next == centralOffset, "local directory length");
        verifyContents(path, names);
        for (int offset : modesToFix) {
            data[offset] = (byte) FILE_644;
            data[offset + 1] = (byte) (FILE_644 >>> 8);
        }
        if (!modesToFix.isEmpty()) Files.write(path, data);
        System.out.println("Normalized SDK AAR modes: " + modesToFix.size() + " entries");
    }
}
