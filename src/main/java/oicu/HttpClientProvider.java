package oicu;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
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
    
    private static final int CHECK_CONNECT_TIMEOUT_MS = 300;
    private static final int USAGE_CONNECT_TIMEOUT_MS = 3000;
    private static final int USAGE_READ_TIMEOUT_MS = 10000;
    
    private static final String BANDWIDTH_TEST_URL = "https://repo1.maven.org/maven2/org/springframework/boot/spring-boot/3.5.0/spring-boot-3.5.0.jar";
    private static final long MIN_ACCEPTABLE_BANDWIDTH_KBPS = 300; // 500 KB/s as minimum threshold
    private static final int BANDWIDTH_READ_BUFFER_SIZE = 8192; // 8 KB buffer for reading
    private static final int BANDWIDTH_TEST_DURATION_MS = 5000; // Test for 5 seconds max
    
    private Set<OkHttpClient> parseProxies(String proxyStrings){
        Set<OkHttpClient> proxyClients = new HashSet<>();
        if (proxyStrings == null) {
            proxyClients.add(createCheckClient(null));
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
                            proxyClients.add(createCheckClient(proxy));
                        }
                    } else{
                        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
                        proxyClients.add(createCheckClient(proxy));
                    }
                } else if (proxyString.isEmpty()) {
                    proxyClients.add(createCheckClient(null));
                } else {
                    System.out.println("Invalid proxy format: " + proxyString);
                }
            }
            return proxyClients;
        }
    }
    
    private OkHttpClient createCheckClient(Proxy proxy) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(CHECK_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (proxy != null) {
            builder.proxy(proxy);
        }
        return builder.build();
    }
    
    private OkHttpClient createUsageClient(Proxy proxy) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(USAGE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(USAGE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (proxy != null) {
            builder.proxy(proxy);
        }
        return builder.build();
    }
    
    @SneakyThrows
    public Set<OkHttpClient> checkClientsLiveness(Set<OkHttpClient> clients, String url, boolean checkBandwidth) {
        List<Thread> threads = new ArrayList<>();
        Set<OkHttpClient> liveClient = ConcurrentHashMap.newKeySet();
        for (OkHttpClient client:clients) {
            Thread thread = new Thread(() -> {
                if (isProxyUrlAlive(client, url)) {
                    // For live proxies, replace the check client with a usage client
                    Proxy proxy = client.proxy();
                    OkHttpClient usageClient = createUsageClient(proxy);
                    
                    // Optional bandwidth check
                    if (checkBandwidth) {
                        double bandwidth = measureBandwidth(usageClient);
                        if (bandwidth >= MIN_ACCEPTABLE_BANDWIDTH_KBPS) {
                            log.info("Proxy {} passed bandwidth test with {} KB/s", 
                                proxy != null ? proxy.address() : "direct", 
                                String.format("%.2f", bandwidth));
                            liveClient.add(usageClient);
                        } else {
                            log.warn("Proxy {} failed bandwidth test with {} KB/s (minimum: {} KB/s)", 
                                proxy != null ? proxy.address() : "direct", 
                                String.format("%.2f", bandwidth),
                                MIN_ACCEPTABLE_BANDWIDTH_KBPS);
                        }
                    } else {
                        // If bandwidth check is disabled, add all responsive proxies
                        liveClient.add(usageClient);
                    }
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
    
    private double measureBandwidth(OkHttpClient client) {
        Request request = new Request.Builder()
                .url(BANDWIDTH_TEST_URL)
                .build();
                
        long startTime = System.currentTimeMillis();
        long endTime = startTime + BANDWIDTH_TEST_DURATION_MS;
        long bytesRead = 0;
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return 0;
            }
            
            ResponseBody body = response.body();
            if (body == null) {
                return 0;
            }
            
            try (InputStream is = body.byteStream()) {
                byte[] buffer = new byte[BANDWIDTH_READ_BUFFER_SIZE];
                int read;
                
                while ((read = is.read(buffer)) != -1) {
                    bytesRead += read;
                    
                    // Stop after the test duration to avoid downloading the entire file
                    if (System.currentTimeMillis() >= endTime) {
                        break;
                    }
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            if (duration <= 0) {
                return 0;
            }
            
            // Calculate bandwidth in KB/s
            return (bytesRead / 1024.0) / (duration / 1000.0);
        } catch (IOException e) {
            return 0;
        }
    }
    
    public HttpClientProvider(String proxyString) {
        Set<OkHttpClient> parsedClients = parseProxies(proxyString);
        // Use bandwidth checking for initial proxy setup
        httpClients.addAll(checkClientsLiveness(parsedClients, "https://repo1.maven.org/maven2/", true));
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

    public boolean isLive(String url) {
        if (affinity.containsKey(url)) {
            return !affinity.get(url).isEmpty();
        } else {
            affinity.put(url, ConcurrentHashMap.newKeySet());
            // Skip bandwidth checking for URL liveness tests
            Set<OkHttpClient> livedProxies = checkClientsLiveness(httpClients, url, false);
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
