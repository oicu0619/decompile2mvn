package oicu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import okhttp3.Response;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;

import static oicu.DatabaseUtils.getDependencyInDb;
import static oicu.JarUtil.calculateJarSimilarity;


public class GAVQueryUtils {
    @SneakyThrows
    private static void searchByHashOnCentral(Dependency dependency, Connection dbConn, HttpClientProvider httpClient) {
        String url = "https://central.sonatype.com/solrsearch/select?q=1:" + dependency.hash + "&rows=20&wt=json";
        Response response = httpClient.curlWithRetry(url);
        if (!response.isSuccessful()) {
            throw new RuntimeException("central sonatype query failed: " + response.code() + " " + response.message());
        }
        String jsonData = response.body().string();
        response.close();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode = objectMapper.readTree(jsonData);
        JsonNode docsNode = rootNode.path("response").path("docs");
        for (JsonNode doc : docsNode) {
            String groupdId = doc.get("g").asText();
            String artifactid = doc.get("a").asText();
            String version = doc.get("v").asText();
            String repo = "https://repo1.maven.org/maven2/";
            // have to check because sometimes maven central hash search is wrong.
            // https://central.sonatype.com/solrsearch/select?q=1:5415a6565bfd65e80fba0c00b161826b67c09abe&wt=json
            String sha1Path = groupdId.replace('.', '/') + "/" + artifactid + "/" + version + "/" + artifactid + "-" + version + ".jar.sha1";
            try (Response sha1Response = httpClient.curlWithRetry(repo + sha1Path)) {
                // otherwise 404 or 302, just ignore.
                if (!sha1Response.isSuccessful()) {
                    continue;
                }
                String body = sha1Response.body().string();
                if (dependency.hash.equals(body)) {
                    dependency.verify(groupdId, artifactid, version, repo);
                    return;
                }
            }
        }
    }

    @SneakyThrows
    private static boolean downloadJarAndCompare(String downloadUrl, Dependency dependency, HttpClientProvider httpClient) {
        try (Response response = httpClient.curlWithRetry(downloadUrl)) {
            if (!response.isSuccessful()) {
                return false;
            }
            Path tempZipPath = Files.createTempFile("downloaded", ".zip");
            File tempZipFile = tempZipPath.toFile();
            tempZipFile.deleteOnExit();
            FileUtils.copyInputStreamToFile(response.body().byteStream(), tempZipFile);
            return calculateJarSimilarity(tempZipPath, dependency.path) == 1;
        }
    }
    @SneakyThrows
    private static void searchByAvOnCentral(Dependency dependency, Connection dbConn, HttpClientProvider httpClient) {
        String url = "https://central.sonatype.com/solrsearch/select?q=a:" + dependency.unverifiedArtifactId + "+AND+v:"+ dependency.unverifiedVersion +"&rows=20&wt=json";
        Response response = httpClient.curlWithRetry(url);
        if (!response.isSuccessful()) {
            throw new RuntimeException("central sonatype query failed: " + response.code() + " " + response.message());
        }
        String jsonData = response.body().string();
        response.close();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode = objectMapper.readTree(jsonData);
        JsonNode docsNode = rootNode.path("response").path("docs");
        for (JsonNode doc : docsNode) {
            String groupdId = doc.get("g").asText();
            String artifactId = doc.get("a").asText();
            String version = doc.get("v").asText();
            String downloadUrl = "https://repo1.maven.org/maven2/"+groupdId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar";
            if (downloadJarAndCompare(downloadUrl, dependency, httpClient)) {
                String repo = "https://repo1.maven.org/maven2/";
                dependency.verify(groupdId, artifactId, version, repo);
                return;
            }
        }
    }

    @SneakyThrows
    private static void searchByGavOnRepos(Dependency dependency, Connection dbConn, HttpClientProvider httpClient, List<String> repos) {
        for (String repo : repos) {
            String sha1Path = dependency.unverifiedGroupId.replace('.', '/') + "/" + dependency.unverifiedArtifactId + "/" + dependency.unverifiedVersion + "/" + dependency.unverifiedArtifactId + "-" + dependency.unverifiedVersion + ".jar.sha1";
            String jarPath = dependency.unverifiedGroupId.replace('.', '/') + "/" + dependency.unverifiedArtifactId + "/" + dependency.unverifiedVersion + "/" + dependency.unverifiedArtifactId + "-" + dependency.unverifiedVersion + ".jar";
            if (!repo.endsWith("/")) {
                repo += "/";
            }
            try (Response response = httpClient.curlWithRetry(repo + sha1Path)) {
                // otherwise 404 or 302, just ignore.
                if (!response.isSuccessful()) {
                    continue;
                }
                String body = response.body().string();
                if (dependency.hash.equals(body)) {
                    dependency.verify(dependency.unverifiedGroupId, dependency.unverifiedArtifactId, dependency.unverifiedVersion, repo);
                    return;
                }
            }
            if (downloadJarAndCompare(repo+jarPath, dependency, httpClient)){
                dependency.verify(dependency.unverifiedGroupId, dependency.unverifiedArtifactId, dependency.unverifiedVersion, repo);
                return;
            }
        }
    }


    private static void searchInDb(Dependency dependency, Connection dbConn, List<String> repos, HttpClientProvider httpClient) {
        if (getDependencyInDb(dependency, dbConn)){
            dependency.verify(dependency.unverifiedGroupId, dependency.unverifiedArtifactId, dependency.unverifiedVersion, dependency.repo);
            if (!repos.contains(dependency.repo) && httpClient.isLive(dependency.repo)){
                repos.add(dependency.repo);        
            }
        }
    }

    public static void checkGAV(Dependency dependency, Connection dbConn, HttpClientProvider httpClient, List<String> repos, List<String> privatePrefixs, List<String> publicPrefixs) {
        if (dependency.isFqcnBelongsPrefixs(privatePrefixs)) {
            return;
        }
        if (dependency.hasGAVInFile()) {
            searchInDb(dependency, dbConn, repos, httpClient);
        }
        if (!dependency.isVerified()) {
            if (dependency.hasGAVInFile()) {
                searchByGavOnRepos(dependency, dbConn, httpClient, repos);
            }
        }
        if (!dependency.isVerified()) {
            searchByHashOnCentral(dependency, dbConn, httpClient);
        }
        if (!dependency.isVerified()) {
            searchByAvOnCentral(dependency, dbConn, httpClient);
        }
        if (!dependency.isVerified() && dependency.isFqcnBelongsPrefixs(publicPrefixs)) {
            dependency.verify("oicu",dependency.unverifiedArtifactId,dependency.unverifiedVersion,"");
        }
    }
}
