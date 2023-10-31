package oicu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.Optional;
import java.util.Properties;
import java.util.Scanner;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static oicu.AssertUtils.assertion;


record GAV (String groupId, String artifactId, String version){}
public class GavUtils {
    private record hashMavenGavVo(String groupId, String artifactId, String version, boolean isPrivate){}

    private static Optional<GAV> getGavFromJar(File jarPackage) throws IOException {
        try (JarFile jarFile = new JarFile(jarPackage)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            boolean found = false;
            Optional<GAV> retVal = Optional.empty();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/maven/") && entry.getName().endsWith("/pom.properties")) {
                    if (found){
                        // if multi pom.properties in the thing, do not return any of them
                        retVal = Optional.empty();
                        break;
                    }
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        Properties properties = new Properties();
                        properties.load(is);
                        String groupId = properties.getProperty("groupId");
                        String artifactId = properties.getProperty("artifactId");
                        String version = properties.getProperty("version");
                        retVal = Optional.of(new GAV(groupId, artifactId, version));
                        found = true;
                    }
                }
            }
            return retVal;
        }
    }
    private static Optional<GAV> searchByHashOnWeb(String hash, File jar_package, Connection dbConn, OkHttpClient httpClient) throws IOException, SQLException {
        String url = "https://search.maven.org/solrsearch/select?q=1:" + hash + "&rows=1&wt=json";
        Request request = new Request.Builder()
                .url(url)
                .build();
        Response response;

        int retry = 0;
        while(true) {
            try {
                if (retry == 3) {
                    System.out.println("search.maven.org timeout 3 times.");
                    System.exit(1);
                }
                retry += 1;
                response = httpClient.newCall(request).execute();
                // use exception as control flow here. Bad code.
                break;
            } catch (Exception e) {
            }
        }
        assertion(response.isSuccessful(), "search.maven do not respond with HTTP 200");
        String jsonData = response.body().string();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode = objectMapper.readTree(jsonData);
        JsonNode docsNode = rootNode.path("response").path("docs");
        response.close();
        if (docsNode.isEmpty()) {
            // thinks the jar is private; let the user decide again.
            // because some package hash is not the same with maven central;
            while(true) {
                System.out.println("\n"+ jar_package.getName() + "'s hash can not be found in maven central. Do you think it is private? (y/n)");
                Scanner scanner = new Scanner(System.in);
                String input = scanner.nextLine();
                if ("y".equalsIgnoreCase(input)) {
                    insertHashMavenGav(dbConn, hash,"","","", true);
                    return Optional.empty();
                } else if ("n".equalsIgnoreCase(input)) {
                    System.out.println("Please carefully insert the GAV. If input wrong you can only modify it in sqlite database");
                    System.out.println("group id:");
                    String groupdId = scanner.nextLine();
                    System.out.println("artifact id:");
                    String artifactid = scanner.nextLine();
                    System.out.println("version:");
                    String version = scanner.nextLine();
                    insertHashMavenGav(dbConn, hash,groupdId,artifactid,version, false);
                    return Optional.of(new GAV(groupdId,artifactid,version));
                } else {
                    System.out.println("Invalid input.");
                }
            }
        } else{
            String groupdId = docsNode.get(0).get("g").asText();
            String artifactid = docsNode.get(0).get("a").asText();
            String version = docsNode.get(0).get("v").asText();
            insertHashMavenGav(dbConn, hash,groupdId,artifactid,version, false);
            return Optional.of(new GAV(groupdId,artifactid,version));
        }
    }

    private static void insertHashMavenGav(Connection dbConn, String hash, String groupdId, String artifactid, String version, boolean isPrivate) throws SQLException {
        PreparedStatement stmt = dbConn.prepareStatement("INSERT INTO hash_maven_gav (hash, group_id, artifact_id, version, is_private) VALUES (?,?,?, ?, ?)");
        stmt.setString(1, hash);
        stmt.setString(2, groupdId);
        stmt.setString(3, artifactid);
        stmt.setString(4, version);
        stmt.setBoolean(5, false);
        stmt.executeUpdate();
    }
    // return: empty means not found
    private static Optional<hashMavenGavVo> searchByHashOnDb(String hash, Connection dbConn) throws SQLException {
        String sql = "SELECT group_id, artifact_id, version,is_private FROM hash_maven_gav WHERE hash = ? LIMIT 1";
        PreparedStatement stmt = dbConn.prepareStatement(sql);
        stmt.setString(1, hash);
        ResultSet rs = stmt.executeQuery();
        if (rs.next()){
            return Optional.of(new hashMavenGavVo(rs.getString("group_id"),rs.getString("artifact_id"),rs.getString("version"),rs.getBoolean("is_private")));
        } else {
            return Optional.empty();
        }
    };

    private static Optional<Boolean> searchByGavOnDb(GAV gav, Connection dbConn) throws SQLException {
        PreparedStatement stmt = dbConn.prepareStatement("SELECT is_private FROM maven_ga WHERE group_id = ? AND artifact_id = ? limit 1");
        stmt.setString(1, gav.groupId());
        stmt.setString(2, gav.artifactId());
        ResultSet rs = stmt.executeQuery();
        if (rs.next()) {
            return Optional.of(rs.getBoolean("is_private"));
        } else {
            return Optional.empty();
        }
    };

    private static boolean searchByGavOnWeb(GAV gav, Connection dbConn, OkHttpClient httpClient) throws SQLException, IOException {
        String MAVEN_REPO_URL = "https://repo1.maven.org/maven2/";
        String path = gav.groupId().replace('.', '/') + "/" + gav.artifactId() + "/" + gav.version() + "/" + gav.artifactId() + "-" + gav.version() + ".pom";
        Request request = new Request.Builder()
                .url(MAVEN_REPO_URL + path)
                .build();
        boolean isPrivate=false;
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                isPrivate = true;
            } else if (response.code() == 404) {
                isPrivate = false;
            } else {
                assertion(false,"http response code error");
            }
        }
        PreparedStatement stmt = dbConn.prepareStatement("INSERT INTO maven_ga (group_id, artifact_id, is_private) VALUES (?, ?, ?)");
        stmt.setString(1, gav.groupId());
        stmt.setString(2, gav.artifactId());
        stmt.setBoolean(3, isPrivate);
        stmt.executeUpdate();
        return isPrivate;
    }
    // return: true for public
    private static boolean searchByGav(GAV gav, Connection dbConn, OkHttpClient httpClient) throws IOException, SQLException, ClassNotFoundException {
        Optional<Boolean> isPrivate = searchByGavOnDb(gav, dbConn);
        if (isPrivate.isPresent()){
            return isPrivate.get();
        } else {
            return searchByGavOnWeb(gav, dbConn, httpClient);
        }
    }

    //  return: empty means private
    private static Optional<GAV> searchByHash(File jar_package, Connection dbConn, OkHttpClient httpClient) throws IOException, SQLException, ClassNotFoundException, NoSuchAlgorithmException {
        String hash = calculateSHA1(jar_package);
        Optional<hashMavenGavVo> result = searchByHashOnDb(hash, dbConn);
        if (result.isPresent()){
            if (result.get().isPrivate){
                return Optional.empty();
            } else {
                return Optional.of(new GAV(result.get().groupId,result.get().artifactId,result.get().version));
            }
        } else {
            return searchByHashOnWeb(hash, jar_package, dbConn, httpClient);
        }
    }
    // try to find GAV in pom.properties
    //  if so, query by GAV
    //  query db about GAV
    //  if not, query repo1.maven.org
    //  if repo1.maven.org is down: assert. maybe can query other repo like aliyun.
    //  add to db
    // else, query by hash
    // query db about hash
    // if not in db, query search.maven.org
    // if search.maven is down: let user search via central.sonatype.com mannually, and tell me the GAV(or private)
    // add to db
    public static Optional<GAV> getGavIfPublic(File jar_package, Connection dbConn, OkHttpClient httpClient) throws IOException, NoSuchAlgorithmException, SQLException, ClassNotFoundException {
        Optional<GAV> gav=  getGavFromJar(jar_package);
        if (gav.isPresent()){
            if(searchByGav(gav.get(), dbConn, httpClient)){
                return gav;
            } else {
                return Optional.empty();
            }
        } else {
            return searchByHash(jar_package, dbConn, httpClient);
        }
    }
    private static String calculateSHA1(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        InputStream fis = new FileInputStream(file);
        int n = 0;
        byte[] buffer = new byte[8192];
        while (n != -1) {
            n = fis.read(buffer);
            if (n > 0) {
                digest.update(buffer, 0, n);
            }
        }
        fis.close();
        return bytesToHex(digest.digest());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
