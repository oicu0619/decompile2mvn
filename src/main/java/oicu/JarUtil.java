package oicu;
import lombok.SneakyThrows;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import java.nio.file.Path;
import java.util.*;

public class JarUtil {
    public static  float calculateJarSimilarity(Path zipFile1, Path zipFile2) {
        Set<String> sources1 = getSources(zipFile1);
        Set<String> sources2 = getSources(zipFile2);
        Set<String> intersection = new HashSet<>(sources1);
        intersection.retainAll(sources2);
        Set<String> union = new HashSet<>(sources1);
        union.addAll(sources2);
        return (float) intersection.size() / union.size();
    }

    @SneakyThrows
    private static Set<String> getSources(Path zipFilePath) {
        Set<String> sources = new HashSet<>();
        try (ZipFile zipFile = new ZipFile(zipFilePath.toFile())) {
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                sources.add(entry.getName());
            }
        }
        return sources;
    }
}