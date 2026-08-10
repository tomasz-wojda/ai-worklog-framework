package ai.worklog.framework.adapters

import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.KeyStore
import java.security.cert.X509Certificate

class InternalSslSupport {
    private static final Map<String, SSLContext> CONTEXTS = [:].asSynchronized()
    private static final TrustManager[] TRUST_ALL = [
        new X509TrustManager() {
            X509Certificate[] getAcceptedIssuers() { null }

            void checkClientTrusted(X509Certificate[] certs, String authType) {}

            void checkServerTrusted(X509Certificate[] certs, String authType) {}
        }
    ] as TrustManager[]

    static void applyHttpsConnection(HttpsURLConnection connection, URI uri) {
        SSLContext context = sslContextFor(uri)
        if (context) {
            connection.setSSLSocketFactory(context.socketFactory)
        }
    }

    static SSLContext sslContextFor(URI uri) {
        if (uri.scheme != 'https' || !uri.host) {
            return null
        }
        int port = uri.port > 0 ? uri.port : 443
        String key = "${uri.host}:${port}"
        synchronized (CONTEXTS) {
            if (CONTEXTS.containsKey(key)) {
                return CONTEXTS[key]
            }
            SSLContext context = buildContext(uri.host, port)
            CONTEXTS[key] = context
            return context
        }
    }

    private static SSLContext buildContext(String host, int port) {
        SSLContext extractContext = SSLContext.getInstance('TLS')
        extractContext.init(null, TRUST_ALL, new java.security.SecureRandom())
        SSLSocket socket = extractContext.socketFactory.createSocket(host, port) as SSLSocket
        try {
            socket.startHandshake()
            X509Certificate[] certs = socket.session.peerCertificates.collect { (X509Certificate) it }
            KeyStore keyStore = KeyStore.getInstance(KeyStore.defaultType)
            keyStore.load(null, 'changeit'.toCharArray())
            certs.eachWithIndex { cert, index ->
                keyStore.setCertificateEntry("host-cert-${index}", cert)
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.defaultAlgorithm)
            factory.init(keyStore)
            SSLContext context = SSLContext.getInstance('TLS')
            context.init(null, factory.trustManagers, new java.security.SecureRandom())
            return context
        } finally {
            socket.close()
        }
    }
}
