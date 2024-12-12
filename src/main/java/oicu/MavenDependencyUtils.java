package oicu;

import me.tongfei.progressbar.ProgressBar;
import okhttp3.OkHttpClient;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.DefaultModelWriter;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;


import static oicu.AssertUtils.assertion;
import static oicu.ExecUtils.decompileJar;
import static oicu.FileUtils.copy_src;
import static oicu.FileUtils.get_pom_path;
import static oicu.GavUtils.getGavIfPublic;


record GA (String groupId, String artifactId){}
public class MavenDependencyUtils {
    public static void iterateDependencies(Path decompile_folder, HashMap<GA, String>publicDep, List<Path>privateDep, Connection dbConn,OkHttpClient httpClient) throws SQLException, IOException, NoSuchAlgorithmException, ClassNotFoundException {
        File lib_folder = decompile_folder.resolve("BOOT-INF/lib").toFile();
        ProgressBar pb = new ProgressBar("checking dependencies via HTTP", lib_folder.listFiles().length);
        for (File file : lib_folder.listFiles()) {
            pb.step();
            pb.refresh();
            assertion(file.getName().endsWith(".jar"), "file under /BOOT-INF/lib is not .jar");
            Optional<GAV> gav = getGavIfPublic(file, dbConn, httpClient);
            if (gav.isEmpty()){
                privateDep.add(Path.of(file.getAbsolutePath()));
            }
            else {
                GA ga = new GA(gav.get().groupId(),gav.get().artifactId());
                if (publicDep.get(ga) != null) {
                      if (publicDep.get(ga).equals(gav.get().version())){
                        System.out.println("same groupid artifactid but different version found in Jar"+file.getName());
                    }
//                    assertion(publicDep.get(ga).equals(gav.get().version()),"same groupid artifactid but different version found in Jar");
                }
                else {
                    publicDep.put(ga,gav.get().version());
                }
            }
        }
        pb.close();
    }

    public static void decompileDependencies(Path decompilerFile, List<Path> privateDep, Path privateDependenciesFolder, Path recompileFolder) throws IOException, ClassNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        for(Path privateDependency: privateDep) {
            Path dstFolder = privateDependenciesFolder.resolve(privateDependency.getFileName().toString().replace(".jar",""));
            //dstFolder can not be named .jar, decompiler will get confused
            decompileJar(decompilerFile, privateDependency, dstFolder);
            copy_src(dstFolder, recompileFolder);
        }
    }

    public static void createPom(Path decompile_folder, Path recompile_folder, HashMap<GA, String>publicDependencies) throws IOException, XmlPullParserException, NoSuchAlgorithmException, InterruptedException, SQLException, ClassNotFoundException {
        Model model = new Model();
        set_java_version(decompile_folder,model);
        add_spring_web_dependencies(model,publicDependencies);
        for(Map.Entry<GA, String> publicDependency: publicDependencies.entrySet()) {
            Dependency dependency = new Dependency();
            dependency.setGroupId(publicDependency.getKey().groupId());
            dependency.setArtifactId(publicDependency.getKey().artifactId());
            dependency.setVersion(publicDependency.getValue());
            model.addDependency(dependency);
        }
        DefaultModelWriter writer = new DefaultModelWriter();
        writer.write(new File(recompile_folder+"/pom.xml"), null, model);
    }

    public static void set_java_version(Path decompile_folder, Model model) throws IOException, XmlPullParserException {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model sourceModel;
        FileReader fileReader = new FileReader(get_pom_path(decompile_folder));
        sourceModel = reader.read(fileReader);
        String[] properies = {"maven.compiler.source","maven.compiler.target"};
        for (String property : properies) {
            String value = sourceModel.getProperties().getProperty(property);
            if (value != null) {
                model.getProperties().setProperty(property, value);
            }
        }
        fileReader.close();
    }

    public static void add_spring_web_dependencies(Model model, HashMap<GA, String>publicDependencies) {
        model.setModelVersion("4.0.0");
        model.setGroupId("oicu");
        model.setArtifactId("recompile");
        model.setVersion("1.0.0");

        if (!publicDependencies.containsKey(new GA("org.springframework.boot","spring-boot-starter-web"))){
            Dependency dependency = new Dependency();
            dependency.setGroupId("org.springframework.boot");
            dependency.setArtifactId("spring-boot-starter-web");
            dependency.setVersion("2.7.17");
            model.addDependency(dependency);
        }

        Plugin plugin = new Plugin();
        plugin.setGroupId("org.springframework.boot");
        plugin.setArtifactId("spring-boot-maven-plugin");
        plugin.setVersion("2.7.17");
        Build build = new Build();
        build.addPlugin(plugin);
        model.setBuild(build);
    }
}