package oicu;

import okhttp3.OkHttpClient;
import org.apache.commons.cli.*;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jetbrains.annotations.NotNull;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static oicu.DatabaseUtils.checkDatabase;
import static oicu.DatabaseUtils.createDbConnection;
import static oicu.ExecUtils.decompileJar;
import static oicu.FileUtils.*;
import static oicu.HttpUtils.createHttpClient;
import static oicu.MavenDependencyUtils.*;

public class Main {
   public static void main(String[] args) throws IOException, NoSuchAlgorithmException, XmlPullParserException, InterruptedException, SQLException, ClassNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        Options options = getOptions();
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp(" ", options);
            System.exit(1);
            return;
        }

        Path jarFile = Path.of(cmd.getOptionValue("input"));
        Path dstFolder = Path.of(cmd.getOptionValue("output"));
        Path decompilerFile = Path.of(cmd.getOptionValue("decompiler"));
        Path databaseFile = Path.of(cmd.getOptionValue("database"));
        String httpProxy = cmd.getOptionValue("proxy");
        Path decompileFolder = dstFolder.resolve("decompile");
        Path privateDependenciesFolder = dstFolder.resolve("private_dependencies");
        Path recompileFolder = dstFolder.resolve("recompile");

        try(Connection dbConn = createDbConnection(databaseFile)) {
            checkDatabase(dbConn);
            checkDstFolderExists(dstFolder);
            create_folders(new Path[]{dstFolder, decompileFolder, privateDependenciesFolder, recompileFolder});
            decompileJar(decompilerFile, jarFile, decompileFolder);
            check_spring_boot_jar(decompileFolder);
            create_recompile_folder_structure(recompileFolder);
            copy_src(decompileFolder.resolve("BOOT-INF/classes"), recompileFolder);
            HashMap<GA, String> publicDependencies = new HashMap<>();
            List<Path> privateDependencies = new ArrayList<>();
            OkHttpClient httpClient = createHttpClient(httpProxy);
            iterateDependencies(decompileFolder, publicDependencies, privateDependencies, dbConn, httpClient);
            decompileDependencies(decompilerFile, privateDependencies, privateDependenciesFolder, recompileFolder);
            createPom(decompileFolder, recompileFolder, publicDependencies);
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

        Option decompiler = new Option("d", "decompiler", true, "decompiler(vineflower.jar) location");
        decompiler.setRequired(true);
        options.addOption(decompiler);

        Option database = new Option("db", "database", true, "sqlite database location");
        database.setRequired(true);
        options.addOption(database);

        Option proxy = new Option("p", "proxy", true, "http proxy, ip:port");
        options.addOption(proxy);
        return options;
    }
}
