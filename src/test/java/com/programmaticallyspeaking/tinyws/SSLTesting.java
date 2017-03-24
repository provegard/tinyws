package com.programmaticallyspeaking.tinyws;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

class SSLTesting {

    private SSLTesting() {}

    private static InputStream keystoreStream(String keystore) {
        InputStream is = SSLTesting.class.getResourceAsStream(keystore);
        if (is == null) throw new IllegalArgumentException("Unknown resource: " + keystore);
        return is;
    }

    static SSLContext createSSLContextForTests(boolean isForServer) throws IOException, GeneralSecurityException {
        // Generated with:
        // keytool -genkey -validity 3650 -keystore "keystore.jks" -storepass "storepassword" -keypass "keypassword" -alias "default" -dname "CN=localhost, OU=MyOrgUnit, O=MyOrg, L=MyCity, S=MyRegion, C=MyCountry" -keyalg RSA
        String KEYSTORE = "/keystore.jks";

        String STOREPASSWORD = "storepassword";
        String KEYPASSWORD = "keypassword";
        String STORETYPE = "JKS";

        KeyStore ks = KeyStore.getInstance(STORETYPE);
        try(InputStream is = keystoreStream(KEYSTORE)) {
            ks.load(is, STOREPASSWORD.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYPASSWORD.toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext sslContext = null;
        sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        return sslContext;
    }
}
