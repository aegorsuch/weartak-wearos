import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class VerifyHostServicesAar {
    private record Classes(byte[] classesJar, Set<String> names) {}

    private static Classes readClasses(Path aarPath) throws IOException {
        Set<String> names = new HashSet<>();
        byte[] classesJar = null;
        int classesJarCount = 0;
        try (ZipFile aar = new ZipFile(aarPath.toFile())) {
            var entries = aar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".jar")) continue;
                byte[] jar;
                try (var input = aar.getInputStream(entry)) {
                    jar = input.readAllBytes();
                }
                if (entry.getName().equals("classes.jar")) {
                    classesJarCount++;
                    classesJar = jar;
                }
                Set<String> jarClasses = new HashSet<>();
                Path temp = Files.createTempFile("weartak-aar-classes-", ".jar");
                try {
                    Files.write(temp, jar);
                    try (ZipFile jarFile = new ZipFile(temp.toFile())) {
                        var jarEntries = jarFile.entries();
                        while (jarEntries.hasMoreElements()) {
                            ZipEntry item = jarEntries.nextElement();
                            if (item.getName().endsWith(".class")) jarClasses.add(item.getName());
                        }
                    }
                } finally {
                    Files.delete(temp);
                }
                Set<String> duplicates = new HashSet<>(names);
                duplicates.retainAll(jarClasses);
                if (!duplicates.isEmpty()) {
                    throw new IllegalStateException("AAR contains duplicate classes across jars: "
                            + String.join(", ", duplicates.stream().sorted().limit(10).toList()));
                }
                names.addAll(jarClasses);
            }
        }
        if (classesJarCount != 1) {
            throw new IllegalStateException("AAR must contain exactly one classes.jar: " + aarPath);
        }
        return new Classes(classesJar, names);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected SDK and host AAR paths");
        Path hostPath = Path.of(args[1]);
        Files.deleteIfExists(hostPath.resolveSibling("verified-classes.sha256"));
        Classes sdk = readClasses(Path.of(args[0]));
        Classes host = readClasses(hostPath);
        List<String> duplicates = new ArrayList<>(sdk.names());
        duplicates.retainAll(host.names());
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException("Host-services AAR duplicates public SDK classes: "
                    + String.join(", ", duplicates.stream().sorted().limit(10).toList()));
        }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(host.classesJar());
        StringBuilder digest = new StringBuilder();
        for (byte value : hash) digest.append(String.format("%02x", value & 0xff));
        Files.writeString(hostPath.resolveSibling("verified-classes.sha256"),
                digest + "  classes.jar\n", StandardCharsets.US_ASCII);
        System.out.println("Verified host-services AAR: " + host.names().size()
                + " classes, no public SDK duplicates; classes.jar SHA-256 " + digest);
    }
}
