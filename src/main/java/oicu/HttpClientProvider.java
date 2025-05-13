package oicu;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.extern.slf4j.XSlf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.io.input.XmlStreamReader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.sleep;

@Slf4j
public class HttpClientProvider {
    private final Set<OkHttpClient> httpClients = ConcurrentHashMap.newKeySet();
    // https://maven.jeecg.org/nexus/content/repositories/jeecg/ can not be accessed via 194.138.0.24
    private final Map<String, Set<OkHttpClient>> affinity = new ConcurrentHashMap<>();
        
    private Set<OkHttpClient> parseProxies(String proxyStrings){
        Set<OkHttpClient> proxyClients = new HashSet<>();
        if (proxyStrings == null) {
            proxyClients.add(new OkHttpClient.Builder().connectTimeout(300, TimeUnit.MILLISECONDS).build());
            return proxyClients;
        } else {
            String[] proxyStringList = proxyStrings.split(",");
            for (String proxyString : proxyStringList) {
                String[] parts = proxyString.split(":");
                if(parts.length == 2) {
                    String host = parts[0];
                    int port = Integer.parseInt(parts[1]);
                    if(host.contains("-")) {
                        String[] ipParts = host.split("\\.");
                        String prefix = ipParts[0] + "." + ipParts[1] + "." + ipParts[2] + ".";
                        int startRange = Integer.parseInt(ipParts[3].split("-")[0]);
                        int endRange = Integer.parseInt(ipParts[3].split("-")[1]);
                        for (int i = startRange; i <= endRange; i++) {
                            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(prefix + i, port));
                            proxyClients.add(new OkHttpClient.Builder().connectTimeout(300, TimeUnit.MILLISECONDS).proxy(proxy).build());
                        }
                    } else{
                        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
                        proxyClients.add(new OkHttpClient.Builder().connectTimeout(300, TimeUnit.MILLISECONDS).proxy(proxy).build());
                    }
                } else if (proxyString.isEmpty()) {
                    proxyClients.add(new OkHttpClient.Builder().connectTimeout(300, TimeUnit.MILLISECONDS).build());
                } else {
                    System.out.println("Invalid proxy format: " + proxyString);
                }
            }
            return proxyClients;
        }
    }
    
    @SneakyThrows
    public Set<OkHttpClient> checkClientsLiveness(Set<OkHttpClient> clients, String url) {
        List<Thread> threads = new ArrayList<>();
        Set<OkHttpClient> liveClient = ConcurrentHashMap.newKeySet();
        for (OkHttpClient client:clients) {
            Thread thread = new Thread(() -> {
                if (isProxyUrlAlive(client, url)) {
                    liveClient.add(client);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        return liveClient;
    }
    
    public HttpClientProvider(String proxyString) {
        Set<OkHttpClient> parsedClients = parseProxies(proxyString);
        httpClients.addAll(checkClientsLiveness(parsedClients, "https://repo1.maven.org/maven2/"));
        System.out.println("Available proxies: " + httpClients.size());
    }
    
    private OkHttpClient getAHttpClient(Set<OkHttpClient> clients){
        return clients.stream()
                .skip(ThreadLocalRandom.current().nextInt(clients.size()))
                .findFirst()
                .orElse(null);
    }
    
    private OkHttpClient getAffineHttpClient(String url){
        for(String baseUrl : affinity.keySet()){
            if(url.startsWith(baseUrl)){
                return getAHttpClient(affinity.get(baseUrl));
            }
        }
        return getAHttpClient(httpClients);
    }
    @SneakyThrows
    public Response curlWithRetry(String url) {
        Request request = new Request.Builder()
                .url(url)
                .build();
        int retry = 0;
        while (true) {
            OkHttpClient httpClient = getAffineHttpClient(url);
            try {
                if (retry == 5) {
                    throw new RuntimeException(url + " timeout 5 times.");
                }
                retry += 1;
                Response response = httpClient.newCall(request).execute(); 
                return response;
            } catch (IOException e) {
                // timeout or connection refused
                sleep(2000);
            }
        }
    }

    public boolean isLive (String url) {
        if (affinity.containsKey(url)) {
            return !affinity.get(url).isEmpty();
        } else {
            affinity.put(url, ConcurrentHashMap.newKeySet());
            Set<OkHttpClient> livedProxies = checkClientsLiveness(httpClients, url);
            affinity.get(url).addAll(livedProxies);
            if (affinity.get(url).isEmpty()) {
                System.out.println("URL " + url + " is not live.");
                return false;
            } else {
                return true;
            }
        }
    }
    private boolean isProxyUrlAlive(OkHttpClient httpClient, String url) {
        Request request = new Request.Builder()
                .url(url)
                .build();
        try (Response ignored = httpClient.newCall(request).execute()){
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
