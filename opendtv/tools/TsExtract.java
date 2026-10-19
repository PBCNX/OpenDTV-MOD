import ph.opendtv.ts.*;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/**
 * Saves every file of every COMPLETE data-carousel module found in a recorded .ts.
 * Usage:  java -cp out TsExtract input.ts outputDir
 * Layout: outputDir/pid_02F8/module_1016/twitter_image6_1.jpg ... plus outputDir/index.txt
 * Incomplete modules are listed in the index but not written (a longer capture completes more of them).
 */
public class TsExtract {
    public static void main(String[] args) throws Exception {
        TsDemux d = new TsDemux();
        byte[] buf = new byte[188 * 500];
        try (InputStream in = new FileInputStream(args[0])) {
            int n;
            while ((n = in.read(buf)) > 0) d.feed(buf, 0, n);
        }
        Path out = Paths.get(args[1]);
        Files.createDirectories(out);

        int files = 0, modules = 0;
        long bytes = 0;
        try (PrintWriter idx = new PrintWriter(Files.newBufferedWriter(out.resolve("index.txt")))) {
            for (CarouselInfo ci : d.getCarousels()) {
                idx.printf("PID 0x%04X: %d/%d modules complete, %d KB of blocks%n",
                        ci.pid, ci.completeModules(), ci.modules.size(), ci.bytesReceived / 1024);
                for (CarouselModule m : ci.modules) {
                    if (!m.complete) {
                        idx.printf("  module 0x%04X v%d  %d bytes  INCOMPLETE %d/%d blocks (not saved)%n",
                                m.moduleId, m.version, m.size, m.blocksReceived, m.blocksTotal);
                        continue;
                    }
                    modules++;
                    Path dir = out.resolve(String.format("pid_%04X", ci.pid)).resolve(String.format("module_%04X", m.moduleId));
                    Files.createDirectories(dir);
                    Files.write(dir.resolve("_module.raw"), m.content);
                    idx.printf("  module 0x%04X v%d  %d bytes  complete%s%n", m.moduleId, m.version, m.content.length,
                            m.compressed ? " (was zlib-compressed)" : "");
                    Set<String> used = new HashSet<>();
                    int unnamed = 0;
                    for (CarouselResource r : m.resources) {
                        String name = safeName(r.location);
                        if (name.isEmpty()) name = "_part" + (unnamed++) + extFor(r.contentType);
                        String unique = name;
                        for (int k = 2; !used.add(unique); k++) unique = k + "_" + name;
                        Files.write(dir.resolve(unique), r.body);
                        files++;
                        bytes += r.body.length;
                        idx.printf("      %-36s %8d B  %s%n", unique, r.length, r.contentType);
                    }
                }
            }
        }
        System.out.printf("Saved %d files (%d KB) from %d complete modules into %s%n", files, bytes / 1024, modules, out);
    }

    /** Keep only a plain relative file name: no directories, no "..", no odd characters. */
    static String safeName(String location) {
        String s = location.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        s = s.replaceAll("[^A-Za-z0-9._-]", "_");
        if (s.equals(".") || s.equals("..")) return "";
        return s;
    }

    static String extFor(String contentType) {
        String t = contentType.toLowerCase();
        if (t.contains("resourcelist")) return ".resourcelist.txt";
        if (t.contains("jpeg")) return ".jpg";
        if (t.contains("png")) return ".png";
        return ".bin";
    }
}
