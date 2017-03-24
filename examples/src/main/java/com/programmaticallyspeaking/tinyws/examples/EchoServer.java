/*
 * Copyright (c) 2017, Per Rovegård <per@rovegard.se>
 *
 * Distributed under the MIT License (license terms are at http://per.mit-license.org, or in the LICENSE file at
 * https://github.com/provegard/tinyws/blob/master/LICENSE).
 */

package com.programmaticallyspeaking.tinyws.examples;

import com.programmaticallyspeaking.tinyws.Server;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class EchoServer {
    public static void main(String[] args) throws IOException, GeneralSecurityException {
        Server.Logger logger = new ConsoleLogger();
        Server.Options options = Server.Options.withPort(9001).andLogger(logger).andBacklog(1024);

        if (args.length == 3) {
            System.out.println("Current directory is " + System.getProperty("user.dir"));
            System.out.println("Will use SSL (args: keystore path (JKS), keystore pass, key pass)");
            SSLContext sslContext = createSSLContext(args[0], args[1], args[2]);
            options = options.andSSL(sslContext);
        }

        Executor executor = Executors.newCachedThreadPool();
        Server ws = new Server(executor, options);
        ws.addHandlerFactory("/", EchoHandler::new);
        try {
            System.out.println("Starting");
            ws.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static SSLContext createSSLContext(String keystorePath, String storePassword, String keyPassword) throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance("JKS");
        File file = new File(keystorePath);
        try(InputStream is = new FileInputStream(file)) {
            ks.load(is, storePassword.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyPassword.toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext sslContext = null;
        sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        return sslContext;
    }
}
