package oicu;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Scanner;
import java.util.stream.Stream;

import static oicu.AssertUtils.assertion;

public class FileUtils {
    public static void checkDstFolderExists(Path dst_folder) throws IOException, InterruptedException {
        File f= dst_folder.toFile();
        if (f.exists()){
            System.out.println("Destination Folder exists. Want to remove it? (y/n)");
            Scanner scanner = new Scanner(System.in);
            String response = scanner.nextLine();
            if ("y".equalsIgnoreCase(response)) {
                org.apache.commons.io.FileUtils.deleteDirectory(f);
            } else if ("n".equalsIgnoreCase(response)) {
                System.out.println("Exiting without removing the folder.");
                System.exit(0);
            } else {
                System.out.println("Invalid input. Exiting.");
                System.exit(1);
            }
        };
    };

    public static void create_folders(Path[] folders) {
        for (Path folder : folders) {
            File f = folder.toFile();
            assertion(!f.exists(),"");
            assertion(f.mkdirs(),f.getAbsolutePath()+" folder is not created successfully");
        }
    }

    public static void create_recompile_folder_structure(Path recompile_folder) {
        create_folders(new Path[]{recompile_folder.resolve("src"),
                recompile_folder.resolve("src/main"),
                recompile_folder.resolve("src/main/java"),
                recompile_folder.resolve("src/main/resources")});
    }

    public static void check_spring_boot_jar(Path decompile_folder){
        if (decompile_folder.resolve("BOOT-INF/classes").toFile().exists() &&
                decompile_folder.resolve("BOOT-INF/lib").toFile().exists() &&
                decompile_folder.resolve("META-INF").toFile().exists() &&
                decompile_folder.resolve("org").toFile().exists()){
            return;
        } else {
            System.out.println("Not a spring boot jar. Exiting.");
            System.exit(0);
        }
    }


    public static String check_only_one_file_and_get(String folder){
        File[] files = new File(folder).listFiles();
        assertion(files.length==1, "");
        return files[0].getName();
    }
    public static String get_pom_path(Path decompile_folder) {
        String group_name = check_only_one_file_and_get(decompile_folder+"/META-INF/maven/");
        String artifact_name = check_only_one_file_and_get(decompile_folder+"/META-INF/maven/"+group_name);
        String pom_file = decompile_folder+"/META-INF/maven/"+group_name+"/"+artifact_name+"/pom.xml";
        assert(new File(pom_file).exists());
        return pom_file;
    }

    public static void copy_src(Path src, Path recompileFolder) throws IOException {
        Path javaFolder = recompileFolder.resolve("src/main/java");
        Path resourceFolder = recompileFolder.resolve("src/main/resources");
        Stream<Path> paths = Files.walk(src);
        Path[] files = paths.toArray(Path[]::new);
        for (Path file: files){
            if (!Files.isRegularFile(file)){
                continue;
            }
            Path relativePath = src.relativize(file);
            Path destPath = file.getFileName().toString().endsWith(".java") ?
                    javaFolder.resolve(relativePath) :
                    resourceFolder.resolve(relativePath);
            Files.createDirectories(destPath.getParent());
            Files.copy(file, destPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
