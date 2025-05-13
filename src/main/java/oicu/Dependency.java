package oicu;


import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.supplier.RepositorySystemSupplier;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static oicu.DatabaseUtils.storeDependencyInDb;

public class Dependency {
    String unverifiedGroupId;
    String unverifiedArtifactId;
    String unverifiedVersion;
    @Getter
    private String verifiedGroupId;
    @Getter
    private String verifiedArtifactId;
    @Getter
    private String verifiedVersion;
    @Getter
    private boolean verified = false;
    private boolean resolved = false;
    private boolean resolvable = false;
    private Connection dbConn;
    @Getter
    private String classPath;
    
    String hash;
    Path path;
    String repo;
    private int hashCode;

    Dependency(Path path, Connection dbConn) {
        this.path = path;
        this.dbConn = dbConn;
        calculateSHA1();
        getGavFromJar();
        getClassFromJar();
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        return obj.hashCode() == hashCode;
    }
    
    @SneakyThrows
    private void calculateSHA1() {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            InputStream fis = new FileInputStream(path.toFile());
            int n = 0;
            byte[] buffer = new byte[8192];
            while (n != -1) {
                n = fis.read(buffer);
                if (n > 0) {
                    digest.update(buffer, 0, n);
                }
            }
            fis.close();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            hash = sb.toString();
            hashCode = Arrays.hashCode(hash.getBytes());
    }
    
    public void verify(String groupId, String artifactId, String version, String repo) {
        if(Objects.equals(version, "5.7.2")){
            System.out.println(1);
        }
        if (!verified){
            verified = true;
            verifiedGroupId = groupId;
            verifiedArtifactId = artifactId;
            verifiedVersion = version;
            this.repo = repo;
            if (!isResolvable()){
                addDepenencyToLocal();
            } else {
                storeDependencyInDb(this,dbConn);
            }
        }
    }

    public boolean hasGAVInFile() {
        return unverifiedGroupId != null && unverifiedArtifactId != null && unverifiedVersion != null;
    }

    @SneakyThrows
    public void getGavFromJar() {
        // groupid from pom.properties
        // AV from file name is could, otherwise from pom.properties
        // file multiple pom.properties , groupid is randomOne
        // gurrantee to have AV after this function.
        if (!path.getFileName().toString().endsWith(".jar")){
            throw new RuntimeException();
        }
        String filename = path.getFileName().toString();
        String nameWithoutExtension = filename.substring(0, filename.length() - 4);
        
        String[] parts = nameWithoutExtension.split("-");
        int versionIndex = -1;
        int count = 0;
        unverifiedArtifactId = "";
        unverifiedVersion = "";
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].matches("[0-9].*")) {
                versionIndex = i;
                count ++;
            }
        }
        if (count == 1) {
            StringBuilder artifactId = new StringBuilder();
            for (int i = 0; i < versionIndex; i++) {
                if (i > 0) {
                    artifactId.append("-");
                }
                artifactId.append(parts[i]);
            }
            StringBuilder version = new StringBuilder();
            for (int i = versionIndex; i < parts.length; i++) {
                if (i > versionIndex) {
                    version.append("-");
                }
                version.append(parts[i]);
            }

            unverifiedArtifactId = artifactId.toString();
            unverifiedVersion = version.toString();
        }
        try (JarFile jarFile = new JarFile(path.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/maven/") && entry.getName().endsWith("/pom.properties")) {
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        Properties properties = new Properties();
                        properties.load(is);
                        if (count !=1){
                            unverifiedArtifactId = properties.getProperty("artifactId");
                            unverifiedVersion = properties.getProperty("version");
                        }
                        unverifiedGroupId = properties.getProperty("groupId");
                        return;
                    }
                }
            }
        } 
    }
    
    @SneakyThrows
    public void getClassFromJar(){
        try (JarFile jarFile = new JarFile(path.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entryName.endsWith(".class")) {
                    classPath = entryName;
                    return;
                }
            }
        }
        classPath = null;
    }
    
    
    public boolean isFqcnBelongsPrefixs(List<String> privatePrefixs){
        if (classPath != null){
            for (String privatePrefix: privatePrefixs){
                if (classPath.startsWith(privatePrefix)){
                    return true;
                }
            }   
        }
        return false;
    }
    
    // based on: https://github.com/apache/maven-resolver/blob/562efa97876b5bec721c1a6c1410306cefe82837/maven-resolver-demos/maven-resolver-demo-snippets/src/main/java/org/apache/maven/resolver/examples/GetDependencyTree.java
    public boolean isResolvable(){
        if (resolved){
            return resolvable;
        }
        resolved = true;
        if(repo == null){
            throw new RuntimeException("repo not defined.");
        }
        if(repo.isEmpty()){
            resolvable = false;
            return false;
        }
        RepositorySystem system = new RepositorySystemSupplier().get();
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepo = new LocalRepository("target/local-repo");
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
        // "org.jeecgframework.boot:jeecg-boot-starter-cloud:2.4.0"
        Artifact artifact = new DefaultArtifact(verifiedGroupId+":"+verifiedArtifactId+":"+verifiedVersion);
        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRoot(new org.eclipse.aether.graph.Dependency(artifact, ""));
        RemoteRepository newCentralRepository = new RemoteRepository.Builder("central", "default", repo).build();
        collectRequest.setRepositories(new ArrayList<>(Collections.singletonList(newCentralRepository)));
        try {
            system.collectDependencies(session, collectRequest);
            resolvable = true;
            return true;
        } catch (Exception e) {
            resolvable = false;
            return false;
        }
    }

    @SneakyThrows
    private void addDepenencyToLocal(){
        verifiedGroupId = "oicu";
        String commandTemplate = "mvn install:install-file -Dfile=%s -DgroupId=%s -DartifactId=%s -Dversion=%s -Dpackaging=jar";
        String cmd = String.format(
                commandTemplate,
                path.toAbsolutePath(),
                verifiedGroupId,
                verifiedArtifactId,
                verifiedVersion
        );
        Runtime.getRuntime().exec(cmd);
    }
}
