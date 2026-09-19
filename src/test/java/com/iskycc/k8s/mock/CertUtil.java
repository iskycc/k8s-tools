package com.iskycc.k8s.mock;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * 测试工具：动态生成自签名证书（模拟 kube-apiserver 的 HTTPS 证书），
 * 其 PEM 内容即模拟 master 上的 /etc/kubernetes/pki/ca.crt。
 */
public final class CertUtil {

    private CertUtil() {
    }

    /** 生成的证书材料包。 */
    public static final class CertBundle {
        private final KeyPair keyPair;
        private final X509Certificate certificate;
        private final String pem;

        CertBundle(KeyPair keyPair, X509Certificate certificate, String pem) {
            this.keyPair = keyPair;
            this.certificate = certificate;
            this.pem = pem;
        }

        public KeyPair getKeyPair() {
            return keyPair;
        }

        public X509Certificate getCertificate() {
            return certificate;
        }

        /** 证书 PEM 文本（等价于 cat ca.crt 的输出）。 */
        public String getPem() {
            return pem;
        }

        /** 构建包含私钥+证书的 PKCS12 KeyStore，供 HttpsServer 使用。 */
        public KeyStore toKeyStore(String alias, char[] password) throws Exception {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry(alias, keyPair.getPrivate(), password,
                    new X509Certificate[]{certificate});
            return ks;
        }
    }

    /**
     * 生成自签名证书。
     *
     * @param cn      主题 CN
     * @param dnsSans DNS SAN，如 localhost
     * @param ipSans  IP SAN，如 127.0.0.1
     */
    public static CertBundle generateSelfSigned(String cn, String[] dnsSans, String[] ipSans)
            throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=" + cn + ",O=iskycc-mock,OU=k8s-tools-e2e");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 24L * 3600 * 1000);
        Date notAfter = new Date(now + 365L * 24 * 3600 * 1000);

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, kp.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));

        List<GeneralName> names = new ArrayList<GeneralName>();
        if (dnsSans != null) {
            for (String dns : dnsSans) {
                names.add(new GeneralName(GeneralName.dNSName, dns));
            }
        }
        if (ipSans != null) {
            for (String ip : ipSans) {
                names.add(new GeneralName(GeneralName.iPAddress, ip));
            }
        }
        if (!names.isEmpty()) {
            builder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(names.toArray(new GeneralName[0])));
        }

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption")
                .build(kp.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(holder);
        cert.verify(kp.getPublic());

        return new CertBundle(kp, cert, toPem(cert));
    }

    /** X509Certificate -> PEM 文本。 */
    public static String toPem(X509Certificate cert) throws Exception {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(cert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n";
    }

    /** PEM 文本 -> X509Certificate（校验用）。 */
    public static X509Certificate fromPem(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    }
}
