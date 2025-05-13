package oicu;

import lombok.SneakyThrows;
import org.apache.commons.cli.*;
import org.apache.maven.model.Model;
import org.apache.maven.model.Repository;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.Thread.sleep;
import static oicu.AssertUtils.assertion;
import static oicu.DatabaseUtils.checkDatabase;
import static oicu.DatabaseUtils.createDbConnection;
import static oicu.VineflowerUtils.decompileJar;
import static oicu.FileSystemUtils.*;
import static oicu.MavenUtils.*;
public class Main {
    @SneakyThrows
   public static void main(String[] args) throws IOException, InterruptedException, SQLException, ClassNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        Options options = getOptions();
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd=null;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            formatter.printHelp(" ", options);
            System.exit(1);
        }

        Path jarFile = Path.of(cmd.getOptionValue("input"));
        Path dstFolder = Path.of(cmd.getOptionValue("output"));
        Path databaseFile = Path.of(cmd.getOptionValue("database"));
        String httpProxy = cmd.getOptionValue("proxy");
        int threadCount = Integer.parseInt(cmd.getOptionValue("threads"));
        Path decompileFolder = dstFolder.resolve("decompile");
        Path privateDependenciesFolder = dstFolder.resolve("private_dependencies");
        Path recompileFolder = dstFolder.resolve("recompile");

        try(Connection dbConn = createDbConnection(databaseFile)) {
            checkDatabase(dbConn);
            checkDstFolderExists(dstFolder);
            create_folders(new Path[]{dstFolder, decompileFolder, privateDependenciesFolder, recompileFolder});
            decompileJar(jarFile, decompileFolder);
            printClassName(decompileFolder);
            check_spring_boot_jar(decompileFolder);
            create_recompile_folder_structure(recompileFolder);
            copy_src(decompileFolder.resolve("BOOT-INF/classes"), recompileFolder);
            HttpClientProvider httpClient = new HttpClientProvider(httpProxy);

         
            Set<Dependency> publicDependencies = ConcurrentHashMap.newKeySet();
            Set<Dependency> privateDependencies = new HashSet<>();
            DependenciesBox dependenciesBox = new DependenciesBox();
            List<String> repos = new CopyOnWriteArrayList<>();
            List<String> privatePrefixs = new CopyOnWriteArrayList<>();
            List<String> publicPrefixs = new CopyOnWriteArrayList<>();
            repos.add("https://repo1.maven.org/maven2/");
            
            try (Reader fileReader = new FileReader(get_one_pom_path(decompileFolder))) {
                MavenXpp3Reader reader = new MavenXpp3Reader();
                Model model;
                model = reader.read(fileReader);
                for (Repository repo : model.getRepositories()) {
                    if(httpClient.isLive(repo.getUrl())){
                        repos.add(repo.getUrl());
                    }
                }
            } catch (Exception ignore) {
                // if do not have pom file, fine.
            }
            
            AtomicBoolean stdio = new AtomicBoolean(false);

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(decompileFolder.resolve("BOOT-INF/lib"))) {
                for (Path entry : stream) {
                    assertion(entry.toString().endsWith(".jar"), "file under /BOOT-INF/lib is not .jar");
                    if (Files.isRegularFile(entry)) {
                        dependenciesBox.addprocessing(new Dependency(entry, dbConn));
                    }
                }
            }
            Runnable iterateDependenciesTask = () -> iterateDependencies(dependenciesBox, publicDependencies, repos, dbConn, httpClient,privatePrefixs, publicPrefixs);
            Runnable askTask = () -> askDependencies(dependenciesBox, publicDependencies, privateDependencies, repos, stdio, httpClient,privatePrefixs, publicPrefixs);
            Runnable status = ()-> printStatus(dependenciesBox, stdio);
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                Thread t = new Thread(iterateDependenciesTask);
                threads.add(t);
                t.start();
            }
            Thread t2 = new Thread(askTask);
            t2.start();
            Thread t3 = new Thread(status);
            t3.start();
            for (Thread t : threads) {
                t.join();
            }
            t3.join();
            t2.join();

            System.out.println("decompiling priv repo");
            decompileDependencies(privateDependencies, privateDependenciesFolder, recompileFolder);
            createPom(decompileFolder, recompileFolder, publicDependencies, privateDependencies, repos , jarFile);
            System.out.println("Decompile All Done");
        }
    }

    @NotNull
    private static Options getOptions() {
        Options options = new Options();
        Option input = new Option("i", "input", true, "input jar file");
        input.setRequired(true);
        options.addOption(input);

        Option output = new Option("o", "output", true, "decompile folder");
        output.setRequired(true);
        options.addOption(output);

//        Option decompiler = new Option("d", "decompiler", true, "decompiler(vineflower.jar) location");
//        decompiler.setRequired(true);
//        options.addOption(decompiler);

        Option database = new Option("db", "database", true, "sqlite database location");
        database.setRequired(true);
        options.addOption(database);

        Option proxy = new Option("p", "proxy", true, "http proxy, ip(range):port like 194.138.0.2-31:9400");
        options.addOption(proxy);
       
        Option thread = new Option("t", "threads", true, "thread count");
        options.addOption(thread);
        return options;
    }
}