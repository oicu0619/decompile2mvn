package oicu;

import okhttp3.OkHttpClient;
import java.net.InetSocketAddress;
import java.net.Proxy;
import static oicu.AssertUtils.assertion;

public class HttpUtils {
    // for keep-alive; do not reestablish tls.
    public static OkHttpClient createHttpClient(String proxyString) {
        if (proxyString != null) {
            String[] parts = proxyString.split(":");
            assertion(parts.length == 2, "Invalid proxy format");
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
            return new OkHttpClient.Builder().proxy(proxy).build();
        } else {
            return new OkHttpClient.Builder().build();
        }
    }
}
