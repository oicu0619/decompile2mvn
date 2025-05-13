package oicu;
import java.nio.file.FileSystem;

import lombok.SneakyThrows;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Repository;
import org.apache.maven.model.io.DefaultModelWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.lang.Thread.sleep;
import static oicu.VineflowerUtils.decompileJar;
import static oicu.FileSystemUtils.copy_src;
import static oicu.GAVQueryUtils.*;


public class MavenUtils {
    @SneakyThrows
    public static void iterateDependencies(DependenciesBox dependenciesBox, Set<Dependency> publicDepencies, List<String> repos, Connection dbConn, HttpClientProvider httpClient, List<String> privatePrefixs , List<String> publicPrefixs)  {
        while (true) {
            if (dependenciesBox.isEmpty()) {
                return;
            }
            Dependency dependency = dependenciesBox.fetchProcessing();
            if (dependency==null) {
                sleep(100);
                continue;
            }
            // if not class file in the dep, drop it
            if (dependency.getClassPath() == null){
                dependenciesBox.settleOneDependency();
                continue;
            }
            checkGAV(dependency, dbConn, httpClient, repos,privatePrefixs , publicPrefixs);
            if (dependency.isVerified()) {
                publicDepencies.add(dependency);
                dependenciesBox.settleOneDependency();
            } else {
                dependenciesBox.addask(dependency);
                dependenciesBox.settleOneDependency();
            }
        }
    }

   
    @SneakyThrows
    public static void printStatus(DependenciesBox dependenciesBox, AtomicBoolean stdio){
        int size = dependenciesBox.processingSize();
        int print = 0;
        while(true){
            if (dependenciesBox.isEmpty()) {
                return;
            }
            sleep(1000);
            int current_size = dependenciesBox.processingSize();
            int processed = size - current_size;
            if(stdio.get()){
                if ( processed - print > 10){
                    print = processed;
                    System.out.println(processed + "/" + size);
                }
            }
        }
    }
    
    @SneakyThrows
    public static void askDependencies(DependenciesBox dependenciesBox, Set<Dependency> publicDependencies, Set<Dependency> privateDependencies, List<String> repos, AtomicBoolean stdio, HttpClientProvider httpClient, List<String> privatePrefixs, List<String> publicPrefixs) {
        while(true){
            if (dependenciesBox.isEmpty()) {
                return;
            }
            Dependency dependency = dependenciesBox.fetchAsk();
            if (dependency==null) {
                sleep(100);
                continue;
            }
            // check is private by class name prefix.
            // find one in ask
            // ask for private / public / public with addtional repo
            // private : add to private repo
            // public : add to public , with randomized gav
            // public with addtional repo:
            // clear all ask, go back to processing, add repo
            if (dependency.isFqcnBelongsPrefixs(privatePrefixs)){
                dependency.verify("oicu",dependency.unverifiedArtifactId,dependency.unverifiedVersion,"");
                privateDependencies.add(dependency);
                dependenciesBox.settleOneDependency();
                continue;
            }
            while(stdio.compareAndSet(true, false)) {
            }
            System.out.println("\n");
            System.out.println("DETAILS:");
            System.out.println("\tname:\t"+ dependency.path.getFileName());
//            System.out.println("\tpath:\t"+dependency.path.toAbsolutePath());
            System.out.println("\tclass:\t"+dependency.getClassPath());
            System.out.println("CHECK:");
            if (dependency.hasGAVInFile()) {
                // protected by cloudflare makes it hard to check by progra
                System.out.println("\thttps://mvnrepository.com/artifact/"+dependency.unverifiedGroupId +"/"+dependency.unverifiedArtifactId +"/"+dependency.unverifiedVersion);
            }
            System.out.println("\thttps://mvnrepository.com/search?q="+dependency.unverifiedArtifactId);
            System.out.println("\thttps://www.google.com/search?q="+dependency.unverifiedArtifactId);
            System.out.println("ACTIONS:");
            System.out.println("\t[pub]\t- Add to public repository");
            System.out.println("\t[priv]\t- Add to private repository");
            System.out.println("\t[pub pre]\t- Add public prefix. For example, if you add io/jmix/, all jar contains io/jmix/* will be considered public");
            System.out.println("\t[priv pre]\t- Add private prefix. For example, if you add com/siemens/, all jar contains com/siemens/* will be considered private");
            System.out.println("\t[add repo]\t- Add a new repo.");
            System.out.println("ENTER COMMAND:");
            //public means do not decompile and use a random groupid artifactid and add to maven local repo
            //private means decompile and add to private repo
            //additional repo means do not decompile and you will provide the repo
            while(true) {
                Scanner scanner = new Scanner(System.in);
                String input = scanner.nextLine();
                if ("priv".equalsIgnoreCase(input)) {
                    dependency.verify("oicu",dependency.unverifiedArtifactId,dependency.unverifiedVersion,"");
                    privateDependencies.add(dependency);
                    dependenciesBox.settleOneDependency();
                    break;
                } else if ("pub".equalsIgnoreCase(input)) {
                    dependency.verify("oicu",dependency.unverifiedArtifactId,dependency.unverifiedVersion,"");
                    publicDependencies.add(dependency);
                    dependenciesBox.settleOneDependency();
                    break;
                } else if ("add repo".equalsIgnoreCase(input)) {
                    System.out.println("input repo url");
                    String input2 = scanner.nextLine();
                    if (httpClient.isLive(input2)) {
                        repos.add(input2);
                    }
                    dependenciesBox.moveAskToProcessing();
                    dependenciesBox.addprocessing(dependency);
                    dependenciesBox.settleOneDependency();
                    break;
                } else if ("priv pre".equalsIgnoreCase(input)) {
                    System.out.println("input private class prefix (like com/siemens/)");
                    String input3 = scanner.nextLine();
                    privatePrefixs.add(input3);
                    dependenciesBox.addask(dependency);
                    dependenciesBox.settleOneDependency();
                    break;
                } else if ("pub pre".equalsIgnoreCase(input)) {
                    System.out.println("input pub class prefix (like io/jmix/)");
                    String input3 = scanner.nextLine();
                    publicPrefixs.add(input3);
                    dependenciesBox.moveAskToProcessing();
                    dependenciesBox.addprocessing(dependency);
                    dependenciesBox.settleOneDependency();
                    break;
                } else {
                    System.out.println("Invalid input.");
                }
            }
            stdio.set(true);
        }
    }

    public static void decompileDependencies(Set<Dependency> privateDep, Path privateDependenciesFolder, Path recompileFolder) throws IOException {
        for (Dependency privateDependency : privateDep) {
            Path dstFolder = privateDependenciesFolder.resolve(privateDependency.path.getFileName().toString().replace(".jar", ""));
            //dstFolder can not be named .jar, decompiler will get confused
            decompileJar(privateDependency.path, dstFolder);
            copy_src(dstFolder, recompileFolder);
        }
    }

    private static void addDependenciesToPom(Set<Dependency> dependencies, Model model){
        for (Dependency dependency : dependencies) {
            org.apache.maven.model.Dependency mavenDependency = new org.apache.maven.model.Dependency();
            mavenDependency.setGroupId(dependency.getVerifiedGroupId());
            mavenDependency.setArtifactId(dependency.getVerifiedArtifactId());
            mavenDependency.setVersion(dependency.getVerifiedVersion());
            model.addDependency(mavenDependency);
        }
    }
    
    @SneakyThrows
    public static void createPom(Path decompile_folder, Path recompile_folder, Set<Dependency> publicDependencies, Set<Dependency> privateDependencies, List<String> repos, Path jarFile) {
        Model model = new Model();
        set_java_version(decompile_folder, model, jarFile);
        add_spring_web_dependencies(model, publicDependencies);
        addDependenciesToPom(publicDependencies,model);
        addDependenciesToPom(privateDependencies,model);
        for (String repo : repos) {
            Repository customRepo = new Repository();
            customRepo.setId(repo.replaceAll("[\\\\/:\"<>|?*]", "")); 
            customRepo.setUrl(repo);
//            RepositoryPolicy customSnapshots = new RepositoryPolicy();
//            customSnapshots.setEnabled(false); // As per your requirement
//            customRepo.setSnapshots(customSnapshots);
            model.addRepository(customRepo);
        }
        DefaultModelWriter writer = new DefaultModelWriter();
        
            writer.write(new File(recompile_folder + "/pom.xml"), null, model);
        
    }

    // do not get bytecode version from pom.xml. From class is more accurate.
    @SneakyThrows
    public static void set_java_version(Path decompile_folder, Model model, Path jarFile) {
//        MavenXpp3Reader reader = new MavenXpp3Reader();
//        Model sourceModel;
//        FileReader fileReader = null;
//        
//        fileReader = new FileReader(get_one_pom_path(decompile_folder));
//        sourceModel = reader.read(fileReader);
//        for (String property : properies) {
//            String value = sourceModel.getProperties().getProperty(property);
//            if (value != null) {
//                set = true;
//                model.getProperties().setProperty(property, value);
//            }
//        }
            String[] properies = {"maven.compiler.source", "maven.compiler.target"};
            boolean set = false;

        // set via bytecode version
        if (!set){


            URI uri = URI.create("jar:" + jarFile.toUri());
            Map<String, ?> env = new HashMap<>();
            try (FileSystem zipFs = FileSystems.newFileSystem(uri, env)) {
                Path root = zipFs.getRootDirectories().iterator().next();
            Path classFile = null;
            try (Stream<Path> stream = Files.walk(root)) {
                classFile = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".class"))
                        .findFirst().get(); 
            }         

            final AtomicInteger majorVersion = new AtomicInteger(-1); 

            try (InputStream is = Files.newInputStream(classFile)) {
                ClassReader cr = new ClassReader(is);
                ClassVisitor cv = new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                        majorVersion.set(version & 0xFFFF); 
                        super.visit(version, access, name, signature, superName, interfaces);
                    }
                };
                cr.accept(cv, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            Map<Integer, String> sourceTargetMap = new HashMap<>();
            Map<Integer, String> releaseMap = new HashMap<>();
            sourceTargetMap.put(49, "1.5"); releaseMap.put(49, "5");  // Java 5
            sourceTargetMap.put(50, "1.6"); releaseMap.put(50, "6");  // Java 6
            sourceTargetMap.put(51, "1.7"); releaseMap.put(51, "7");  // Java 7
            sourceTargetMap.put(52, "1.8"); releaseMap.put(52, "8");  // Java 8 (Note difference!)
            sourceTargetMap.put(53, "9");   releaseMap.put(53, "9");  // Java 9
            sourceTargetMap.put(54, "10");  releaseMap.put(54, "10"); // Java 10
            sourceTargetMap.put(55, "11");  releaseMap.put(55, "11"); // Java 11
            sourceTargetMap.put(56, "12");  releaseMap.put(56, "12"); // Java 12
            sourceTargetMap.put(57, "13");  releaseMap.put(57, "13"); // Java 13
            sourceTargetMap.put(58, "14");  releaseMap.put(58, "14"); // Java 14
            sourceTargetMap.put(59, "15");  releaseMap.put(59, "15"); // Java 15
            sourceTargetMap.put(60, "16");  releaseMap.put(60, "16"); // Java 16
            sourceTargetMap.put(61, "17");  releaseMap.put(61, "17"); // Java 17
            sourceTargetMap.put(62, "18");  releaseMap.put(62, "18"); // Java 18
            sourceTargetMap.put(63, "19");  releaseMap.put(63, "19"); // Java 19
            sourceTargetMap.put(64, "20");  releaseMap.put(64, "20"); // Java 20
            sourceTargetMap.put(65, "21");  releaseMap.put(65, "21"); // Java 21
            sourceTargetMap.put(66, "22");  releaseMap.put(66, "22"); //
            for (String property : properies) {
                model.getProperties().setProperty(property, sourceTargetMap.get(majorVersion.get()));
            }
        }
        }
    }

    public static void add_spring_web_dependencies(Model model, Set<Dependency> publicDependencies) {
        model.setModelVersion("4.0.0");
        model.setGroupId("oicu");
        model.setArtifactId("recompile");
        model.setVersion("1.0.0");
        
//        if (!publicDependencies.containsKey(new GA("org.springframework.boot", "spring-boot-starter-web"))) {
//            Dependency dependency = new Dependency();
//            dependency.setGroupId("org.springframework.boot");
//            dependency.setArtifactId("spring-boot-starter-web");
//            dependency.setVersion("2.7.17");
//            model.addDependency(dependency);
//        }

        Plugin plugin = new Plugin();
        plugin.setGroupId("org.springframework.boot");
        plugin.setArtifactId("spring-boot-maven-plugin");
        plugin.setVersion("2.7.17");
        Build build = new Build();
        build.addPlugin(plugin);
        model.setBuild(build);
    }
}