package com.argsecure.rastagmobility;

import com.argsecure.mobility.api.LinkHealth;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.*;
import okhttp3.*;

/** Trusted USB import only; never wired into conversation tools, backup, or public UI. */
final class EspPairing {
    private static final String ALIAS = "rastag_hardware_pair_v1";
    final String device, hostname, hint;
    final OkHttpClient client;
    interface Address { String get(); }
    private EspPairing(JsonObject o, Address address) throws Exception {
        if (o.get("version").getAsInt() != 1) throw new IOException("Pairing version");
        device = o.get("device").getAsString(); hostname = o.get("hostname").getAsString();
        hint = o.has("ip_hint") ? o.get("ip_hint").getAsString() : "";
        if (!device.matches("rastag-esp-[0-9a-f]{12}") || !hostname.equals(device+".local")
                || (!hint.isEmpty() && !hint.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) throw new IOException("Pairing identity");
        X509Certificate ca = cert(o.get("ca").getAsString()), cert = cert(o.get("client_cert").getAsString());
        ca.checkValidity(); cert.checkValidity(); cert.verify(ca.getPublicKey());
        String pem = o.get("client_key").getAsString().replace("-----BEGIN PRIVATE KEY-----","")
                .replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");
        byte[] der = Base64.decode(pem, Base64.DEFAULT);
        java.security.PrivateKey key;
        try { key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der)); }
        finally { Arrays.fill(der,(byte)0); }
        KeyStore identity = KeyStore.getInstance(KeyStore.getDefaultType()); identity.load(null);
        identity.setKeyEntry("client", key, new char[0], new java.security.cert.Certificate[]{cert,ca});
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(identity,new char[0]);
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null); trust.setCertificateEntry("pair",ca);
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(trust);
        X509TrustManager manager = (X509TrustManager)tm.getTrustManagers()[0];
        SSLContext tls = SSLContext.getInstance("TLSv1.2"); tls.init(km.getKeyManagers(),new TrustManager[]{manager},null);
        String pin = o.get("server_pin").getAsString();
        if (!pin.matches("sha256/[A-Za-z0-9+/]{43}=")) throw new IOException("Pairing pin");
        client = new OkHttpClient.Builder().sslSocketFactory(tls.getSocketFactory(),manager)
                .certificatePinner(new CertificatePinner.Builder().add(hostname,pin).build())
                .connectionSpecs(Collections.singletonList(new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_2).build()))
                .dns(host -> {
                    String ip = address.get();
                    if (!host.equals(hostname) || ip == null || ip.isEmpty()) throw new java.net.UnknownHostException();
                    return Collections.singletonList(java.net.InetAddress.getByName(ip));
                }).proxy(java.net.Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .connectTimeout(3,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).writeTimeout(3,TimeUnit.SECONDS)
                .callTimeout(4,TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
    }
    private static X509Certificate cert(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }
    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey)ks.getKey(ALIAS,null);
        KeyGenerator g = KeyGenerator.getInstance("AES","AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return g.generateKey();
    }
    static EspPairing load(Context context, Address address) throws Exception {
        File dir = context.getNoBackupFilesDir(), incoming = new File(dir,"hardware_pair_import.json");
        AtomicFile saved = new AtomicFile(new File(dir,"hardware_pair_v1.enc"));
        byte[] raw;
        if (incoming.exists()) {
            try (InputStream in = new FileInputStream(incoming)) { raw = read(in,16384); }
            try {
                EspPairing pair = new EspPairing(JsonParser.parseString(new String(raw,StandardCharsets.UTF_8)).getAsJsonObject(),address);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key());
                byte[] encrypted = c.doFinal(raw), iv = c.getIV();
                FileOutputStream out = saved.startWrite();
                try { out.write(iv); out.write(encrypted); saved.finishWrite(out); }
                catch (Exception e) { saved.failWrite(out); throw e; }
                if (!incoming.delete()) throw new IOException("Pairing import cleanup");
                return pair;
            } finally {
                Arrays.fill(raw,(byte)0);
                if (incoming.exists()) incoming.delete();
            }
        }
        if (!saved.getBaseFile().exists()) return null;
        try (InputStream in = saved.openRead()) { raw = read(in,16384); }
        if (raw.length < 29) throw new IOException("Pairing storage");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,raw,0,12));
        byte[] clear = c.doFinal(raw,12,raw.length-12);
        try { return new EspPairing(JsonParser.parseString(new String(clear,StandardCharsets.UTF_8)).getAsJsonObject(),address); }
        finally { Arrays.fill(clear,(byte)0); }
    }
    static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[1024]; int n;
        while ((n = in.read(buf)) != -1) { if (out.size()+n > limit) throw new IOException("Size limit"); out.write(buf,0,n); }
        return out.toByteArray();
    }
}
