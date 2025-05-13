package oicu;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
import java.io.OutputStream;
import java.io.PrintStream;
;

public class VineflowerUtils {
    public static void decompileJarLegacy(Path decompilerFile, Path jarFile, Path dstFolder) throws IOException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        System.out.println("decompiling "+jarFile);
        // use reflection to invoke decompiler, not runtime.getRuntime.exec, because:
        // 1. windows/linux compatibility
        // 2. do not need to check jdk under PATH is greater than 11, which is needed by vineflower.
        URL jarUrl = new URL("file:"+decompilerFile.toAbsolutePath());

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarUrl})){
            Class<?> mainClass = classLoader.loadClass("org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler");
            java.lang.reflect.Method mainMethod = mainClass.getMethod("main", String[].class);
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            PrintStream nullOutputStream = new PrintStream(new OutputStream() {
                @Override
                public void write(int b) {
                    // do nothing
                }
            });
            System.setOut(nullOutputStream);
            System.setErr(nullOutputStream);
            mainMethod.invoke(null, (Object) new String[] {"--verify-merges=1", jarFile.toString(), dstFolder.toString()});
            System.setOut(originalOut);
            System.setErr(originalErr);
        } catch (ClassNotFoundException e){
            System.out.println("decompiler main class not found. Maybe jar location wrong?");
        }
        System.out.println("decompile done");
    }
    public static void decompileJar(Path jarFile, Path dstFolder) {
        PrintStream nullOutputStream = new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                // do nothing
            }
        });
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(nullOutputStream);
        System.setErr(nullOutputStream);
        org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler.main(new String[] {"--verify-merges=1", jarFile.toString(), dstFolder.toString()});
        System.setOut(originalOut);
        System.setErr(originalErr);
    }
}
