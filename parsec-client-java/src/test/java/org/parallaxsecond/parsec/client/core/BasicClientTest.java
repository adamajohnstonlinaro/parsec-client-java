package org.parallaxsecond.parsec.client.core;

import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parallaxsecond.parsec.client.core.ipc_handler.IpcHandler;
import org.parallaxsecond.parsec.client.jna.Uid;
import org.parallaxsecond.parsec.protocol.operations.NativeResult;
import org.parallaxsecond.parsec.protobuf.psa_algorithm.PsaAlgorithm;
import org.parallaxsecond.parsec.protocol.requests.Opcode;
import org.parallaxsecond.testcontainers.ParsecContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.io.File;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class BasicClientTest {

  @Container
  ParsecContainer parsecContainer =
      ParsecContainer.withVersion("0.8.1")
          .withFileSystemBind(
              new File("src/test/resources/mbed-crypto-config.toml").getAbsolutePath(),
              "/etc/parsec/config.toml");

  private BasicClient client;
  private final String eccKey = "eccKey";
  private final String rsaKey = "rsaKey";

  @BeforeEach
  void setup() {
    // uid of the parse user in docker
    Uid.IMPL.set(() -> 4000);
    Awaitility.await().until(parsecContainer::isRunning);
    this.client =
        BasicClient.client(
            "parsec-tool", IpcHandler.connectFromUrl(parsecContainer.getSocketUri()));
    parsecContainer.parsecTool("create-ecc-key", "--key-name", eccKey);
    parsecContainer.parsecTool("create-rsa-key", "--key-name", rsaKey, "--for-signing");
  }
  /**
   * would be good to have this dockerized ssh can forward AF_UNIX sockets
   *
   * <pre>
   *  if on a mac forward the parsec socket to your local machine.
   *
   *  ssh -L/tmp/parsec.sock:/remote/home/parsec.sock 192.168.0.22
   *
   * </pre>
   */
  @Test
  void ping() {
    NativeResult.PingResult res = client.ping();
    assertEquals(Opcode.PING, res.getOpcode());
    assertEquals(1, res.getWireProtocolVersionMaj());
    assertEquals(0, res.getWireProtocolVersionMin());
  }

  @Test
  @SneakyThrows
  void listKeys() {
    NativeResult.ListKeysResult keys = client.listKeys();
    assertEquals(2, keys.getKeys().size());
  }

  @Test
  @SneakyThrows
  void hash() {
    PsaAlgorithm.Algorithm.AsymmetricSignature keyargs =
        PsaAlgorithm.Algorithm.AsymmetricSignature.newBuilder()
            .setRsaPkcs1V15Sign(
                PsaAlgorithm.Algorithm.AsymmetricSignature.RsaPkcs1v15Sign.newBuilder()
                    .setHashAlg(
                        PsaAlgorithm.Algorithm.AsymmetricSignature.SignHash.newBuilder()
                            .setSpecific(PsaAlgorithm.Algorithm.Hash.SHA_256)
                            .build())
                    .build())
            .build();

    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    NativeResult.PsaSignHashResult hashResult = client.psaSignHash(rsaKey, bytes, keyargs);
    byte[] signature = hashResult.getSignature();
    assertNotNull(signature);

    NativeResult.PsaVerifyHashResult verifiedResult =
        client.psaVerifyHash(rsaKey, bytes, keyargs, signature);
    assertNotNull(verifiedResult);

    try {
      bytes[0] += 1;
      client.psaVerifyHash(rsaKey, bytes, keyargs, signature);
      fail("signature must no verify");
    } catch (Exception e) {
      // OK
    }
  }

  @Test
  void generateRandom() {
    long length = 512L;

    byte[] randomBytes  = client.psaGenerateRandom(length);

    assertNotNull(randomBytes);
    assertEquals((long)randomBytes.length, length);
  }

}
