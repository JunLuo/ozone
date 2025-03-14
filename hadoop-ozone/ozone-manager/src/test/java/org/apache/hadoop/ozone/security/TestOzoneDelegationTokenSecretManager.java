/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.security;

import java.io.File;
import java.io.IOException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.security.x509.SecurityConfig;
import org.apache.hadoop.hdds.security.x509.certificate.client.CertificateClient;
import org.apache.hadoop.hdds.security.x509.certificate.client.OMCertificateClient;
import org.apache.hadoop.hdds.server.ServerUtils;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OmMetadataManagerImpl;
import org.apache.hadoop.ozone.om.S3SecretManager;
import org.apache.hadoop.ozone.om.S3SecretManagerImpl;
import org.apache.hadoop.ozone.om.helpers.S3SecretValue;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.ssl.KeyStoreTestUtil;
import org.apache.hadoop.security.token.SecretManager;
import org.apache.hadoop.security.token.Token;
import org.apache.ozone.test.LambdaTestUtils;
import org.apache.hadoop.util.Time;

import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_RATIS_ENABLE_KEY;
import static org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMTokenProto.Type.S3AUTHINFO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Test class for {@link OzoneDelegationTokenSecretManager}.
 */
public class TestOzoneDelegationTokenSecretManager {

  private OzoneDelegationTokenSecretManager secretManager;
  private SecurityConfig securityConfig;
  private CertificateClient certificateClient;
  private long expiryTime;
  private Text serviceRpcAdd;
  private OzoneConfiguration conf;
  private static final Text TEST_USER = new Text("testUser");
  private long tokenMaxLifetime = 1000 * 20;
  private long tokenRemoverScanInterval = 1000 * 20;
  private S3SecretManager s3SecretManager;
  private String s3Secret = "dbaksbzljandlkandlsd";

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @Before
  public void setUp() throws Exception {
    conf = createNewTestPath();
    securityConfig = new SecurityConfig(conf);
    certificateClient = setupCertificateClient();
    certificateClient.init();
    expiryTime = Time.monotonicNow() + 60 * 60 * 24;
    serviceRpcAdd = new Text("localhost");
    final Map<String, String> s3Secrets = new HashMap<>();
    s3Secrets.put("testuser1", s3Secret);
    s3Secrets.put("abc", "djakjahkd");
    OMMetadataManager metadataManager = new OmMetadataManagerImpl(conf);
    s3SecretManager = new S3SecretManagerImpl(conf, metadataManager) {
      @Override
      public S3SecretValue getS3Secret(String kerberosID) {
        if (s3Secrets.containsKey(kerberosID)) {
          return new S3SecretValue(kerberosID, s3Secrets.get(kerberosID));
        }
        return null;
      }

      @Override
      public String getS3UserSecretString(String awsAccessKey) {
        if (s3Secrets.containsKey(awsAccessKey)) {
          return s3Secrets.get(awsAccessKey);
        }
        return null;
      }
    };
  }

  private OzoneConfiguration createNewTestPath() throws IOException {
    OzoneConfiguration config = new OzoneConfiguration();
    // When ratis is enabled, tokens are not updated to the store directly by
    // OzoneDelegationTokenSecretManager. Tokens are updated via Ratis
    // through the DoubleBuffer. Hence, to test
    // OzoneDelegationTokenSecretManager, we should disable OM Ratis.
    // TODO: Once HA and non-HA code paths are merged in
    //  OzoneDelegationTokenSecretManager, this test should be updated to
    //  test both ratis enabled and disabled case.
    config.setBoolean(OZONE_OM_RATIS_ENABLE_KEY, false);
    File newFolder = folder.newFolder();
    if (!newFolder.exists()) {
      Assert.assertTrue(newFolder.mkdirs());
    }
    ServerUtils.setOzoneMetaDirPath(config, newFolder.toString());
    return config;
  }

  /**
   * Helper function to create certificate client.
   * */
  private CertificateClient setupCertificateClient() throws Exception {
    KeyPair keyPair = KeyStoreTestUtil.generateKeyPair("RSA");
    X509Certificate cert = KeyStoreTestUtil
        .generateCertificate("CN=OzoneMaster", keyPair, 30, "SHA256withRSA");

    return new OMCertificateClient(securityConfig) {
      @Override
      public X509Certificate getCertificate() {
        return cert;
      }

      @Override
      public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
      }

      @Override
      public PublicKey getPublicKey() {
        return keyPair.getPublic();
      }

      @Override
      public X509Certificate getCertificate(String serialId) {
        return cert;
      }
    };
  }

  @After
  public void tearDown() throws IOException {
    secretManager.stop();
  }

  @Test
  public void testCreateToken() throws Exception {
    secretManager = createSecretManager(conf, tokenMaxLifetime,
        expiryTime, tokenRemoverScanInterval);
    secretManager.start(certificateClient);
    Token<OzoneTokenIdentifier> token = secretManager.createToken(TEST_USER,
        TEST_USER, TEST_USER);
    OzoneTokenIdentifier identifier =
        OzoneTokenIdentifier.readProtoBuf(token.getIdentifier());
    // Check basic details.
    Assert.assertTrue(identifier.getRealUser().equals(TEST_USER));
    Assert.assertTrue(identifier.getRenewer().equals(TEST_USER));
    Assert.assertTrue(identifier.getOwner().equals(TEST_USER));

    validateHash(token.getPassword(), token.getIdentifier());
  }

  private void restartSecretManager() throws IOException {
    secretManager.stop();
    secretManager = null;
    secretManager = createSecretManager(conf, tokenMaxLifetime,
        expiryTime, tokenRemoverScanInterval);
  }

  private void testRenewTokenSuccessHelper(boolean restartSecretManager)
      throws Exception {
    secretManager = createSecretManager(conf, tokenMaxLifetime,
        expiryTime, tokenRemoverScanInterval);
    secretManager.start(certificateClient);
    Token<OzoneTokenIdentifier> token = secretManager.createToken(TEST_USER,
        TEST_USER,
        TEST_USER);
    Thread.sleep(10 * 5);

    if (restartSecretManager) {
      restartSecretManager();
    }

    long renewalTime = secretManager.renewToken(token, TEST_USER.toString());
    Assert.assertTrue(renewalTime > 0);
  }

  @Test
  public void testReloadAndRenewToken() throws Exception {
    testRenewTokenSuccessHelper(true);
  }

  @Test
  public void testRenewTokenSuccess() throws Exception {
    testRenewTokenSuccessHelper(false);
  }

  /**
   * Tests failure for mismatch in renewer.
   */
  @Test
  public void testRenewTokenFailure() throws Exception {
    secretManager = createSecretManager(conf, tokenMaxLifetime,
        expiryTime, tokenRemoverScanInterval);
    secretManager.start(certificateClient);
    Token<OzoneTokenIdentifier> token = secretManager.createToken(TEST_USER,
        TEST_USER, TEST_USER);
    LambdaTestUtils.intercept(AccessControlException.class,
        "rougeUser tries to renew a token", () -> {
          secretManager.renewToken(token, "rougeUser");
        });
  }

  /**
   * Tests token renew failure due to max time.
   */
  @Test
  public void testRenewTokenFailureMaxTime() throws Exception {
    secretManager = createSecretManager(conf, 100,
        100, tokenRemoverScanInterval);
    secretManager.start(certificateClient);
    Token<OzoneTokenIdentifier> token = secretManager.createToken(TEST_USER,
        TEST_USER,
        TEST_USER);
    Thread.sleep(101);
    LambdaTestUtils.intercept(IOException.class,
        "testUser tried to renew an expired token", () -> {
          secretManager.renewToken(token, TEST_USER.toString());
        });
  }

  /**
   * Tests token renew failure due to renewal time.
   */
  @Test
  public void testRenewTokenFailureRenewalTime() throws Exception {
    secretManager = createSecretManager(conf, 1000 * 10,
        10, tokenRemoverScanInterval);
    secretManager.start(certificateClient);
    Token<OzoneTokenIdentifier> token = secretManager.createToken(TEST_USER,
        TEST_USER,
        TEST_USER);
    Thread.sleep(15);
    LambdaTestUtils.intercept(IOException.class, "is expired", () -> {
      secretManager.renewToken(token, TEST_USER.toString());
    });
  }

  @Test
  public void testCreateIdentifier() throws Exception {
    secretManager = createSecretManager(conf, tokenMaxLifetime,
        expiryTime, tokenRemoverScanInterval);
    secretManager.start(certificateClient);
    OzoneTokenIdentifier identifier = secretManager.createIdentifier();
    // Check basic details.
    Assert.assertTrue(identifier.getOwner().equals(new Text("")));
    Assert.assertTrue(identifier.getRealUser().equals(new Text("")));
    Assert.assertTrue(identifier.getRenewer().equals(new Text("")));
  }

  @Test
  public void testCancelTokenSuccess() throws Exception {
    secretManager = createSecretManager(conf, tokenMaxLifetime,
        expiryTime, tokenRemoverScanInterval);
    secretManager.start(certificateClient);
    Token<OzoneTokenIdentifier> token = secretManager.createToken(TEST_USER,
        TEST_USER, TEST_USER);
    secretManager.cancelToken(token, TEST_USER.toString());
  }

  @Test
  public void testCancelTokenFailure() throws Exception {
    secretManager = createSecretManager(conf, tokenMaxLifetime,
        expiryTime, tokenRemoverScanInterval);
    secretManager.start(certificateClient);
    Token<OzoneTokenIdentifier> token = secretManager.createToken(TEST_USER,
        TEST_USER,
        TEST_USER);
    LambdaTestUtils.intercept(AccessControlException.class,
        "rougeUser is not authorized to cancel the token", () -> {
          secretManager.cancelToken(token, "rougeUser");
        });
  }

  @Test
  public void testVerifySignatureSuccess() throws Exception {
    secretManager = createSecretManager(conf, tokenMaxLifetime,
        expiryTime, tokenRemoverScanInterval);
    secretManager.start(certificateClient);
    OzoneTokenIdentifier id = new OzoneTokenIdentifier();
    id.setOmCertSerialId(certificateClient.getCertificate()
        .getSerialNumber().toString());
    id.setMaxDate(Time.now() + 60 * 60 * 24);
    id.setOwner(new Text("test"));
    Assert.assertTrue(secretManager.verifySignature(id,
        certificateClient.signData(id.getBytes())));
  }

  @Test
  public void testVerifySignatureFailure() throws Exception {
    secretManager = createSecretManager(conf, tokenMaxLifetime,
        expiryTime, tokenRemoverScanInterval);
    secretManager.start(certificateClient);
    OzoneTokenIdentifier id = new OzoneTokenIdentifier();
    // set invalid om cert serial id
    id.setOmCertSerialId("1927393");
    id.setMaxDate(Time.now() + 60 * 60 * 24);
    id.setOwner(new Text("test"));
    Assert.assertFalse(secretManager.verifySignature(id, id.getBytes()));
  }

  @Test
  public void testValidateS3AUTHINFOSuccess() throws Exception {
    secretManager = createSecretManager(conf, tokenMaxLifetime,
        expiryTime, tokenRemoverScanInterval);
    secretManager.start(certificateClient);

    OzoneTokenIdentifier identifier = new OzoneTokenIdentifier();
    identifier.setTokenType(S3AUTHINFO);
    identifier.setSignature("56ec73ba1974f8feda8365c3caef89c5d4a688d" +
        "5f9baccf4765f46a14cd745ad");
    identifier.setStrToSign("AWS4-HMAC-SHA256\n" +
        "20190221T002037Z\n" +
        "20190221/us-west-1/s3/aws4_request\n" +
        "c297c080cce4e0927779823d3fd1f5cae71481a8f7dfc7e18d91851294efc47d");
    identifier.setAwsAccessId("testuser1");
    identifier.setOwner(new Text("testuser1"));
    secretManager.retrievePassword(identifier);
  }

  @Test
  public void testValidateS3AUTHINFOFailure() throws Exception {
    secretManager = createSecretManager(conf, tokenMaxLifetime,
        expiryTime, tokenRemoverScanInterval);
    secretManager.start(certificateClient);

    OzoneTokenIdentifier identifier = new OzoneTokenIdentifier();
    identifier.setTokenType(S3AUTHINFO);
    identifier.setSignature("56ec73ba1974f8feda8365c3caef89c5d4a688d" +
        "5f9baccf4765f46a14cd745ad");
    identifier.setStrToSign("AWS4-HMAC-SHA256\n" +
        "20190221T002037Z\n" +
        "20190221/us-west-1/s3/aws4_request\n" +
        "c297c080cce4e0927779823d3fd1f5cae71481a8f7dfc7e18d91851294efc47d");
    identifier.setAwsAccessId("testuser2");
    identifier.setOwner(new Text("testuser2"));
    // Case 1: User don't have aws secret set.
    LambdaTestUtils.intercept(SecretManager.InvalidToken.class, " No S3 " +
            "secret found for S3 identifier",
        () -> secretManager.retrievePassword(identifier));

    // Case 2: Invalid hash in string to sign.
    identifier.setStrToSign("AWS4-HMAC-SHA256\n" +
        "20190221T002037Z\n" +
        "20190221/us-west-1/s3/aws4_request\n" +
        "c297c080cce4e0927779823d3fd1f5cae71481a8f7dfc7e18d91851294efc47d" +
        "+invalidhash");
    LambdaTestUtils.intercept(SecretManager.InvalidToken.class, " No S3 " +
            "secret found for S3 identifier",
        () -> secretManager.retrievePassword(identifier));

    // Case 3: Invalid hash in authorization hmac.
    identifier.setSignature("56ec73ba1974f8feda8365c3caef89c5d4a688d" +
        "+invalidhash" + "5f9baccf4765f46a14cd745ad");
    identifier.setStrToSign("AWS4-HMAC-SHA256\n" +
        "20190221T002037Z\n" +
        "20190221/us-west-1/s3/aws4_request\n" +
        "c297c080cce4e0927779823d3fd1f5cae71481a8f7dfc7e18d91851294efc47d");
    LambdaTestUtils.intercept(SecretManager.InvalidToken.class, " No S3 " +
            "secret found for S3 identifier",
        () -> secretManager.retrievePassword(identifier));
  }

  /**
   * Validate hash using public key of KeyPair.
   */
  private void validateHash(byte[] hash, byte[] identifier) throws Exception {
    Signature rsaSignature =
        Signature.getInstance(securityConfig.getSignatureAlgo(),
            securityConfig.getProvider());
    rsaSignature.initVerify(certificateClient.getPublicKey());
    rsaSignature.update(identifier);
    Assert.assertTrue(rsaSignature.verify(hash));
  }

  /**
   * Create instance of {@link OzoneDelegationTokenSecretManager}.
   */
  private OzoneDelegationTokenSecretManager
      createSecretManager(OzoneConfiguration config, long tokenMaxLife,
      long expiry, long tokenRemoverScanTime) throws IOException {
    return new OzoneDelegationTokenSecretManager.Builder()
        .setConf(config)
        .setTokenMaxLifetime(tokenMaxLife)
        .setTokenRenewInterval(expiry)
        .setTokenRemoverScanInterval(tokenRemoverScanTime)
        .setService(serviceRpcAdd)
        .setS3SecretManager(s3SecretManager)
        .setCertificateClient(certificateClient)
        .setOmServiceId(OzoneConsts.OM_SERVICE_ID_DEFAULT)
        .build();
  }
}