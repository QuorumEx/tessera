package com.quorum.tessera.transaction.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.*;

import com.quorum.tessera.enclave.*;
import com.quorum.tessera.encryption.PublicKey;
import com.quorum.tessera.transaction.EncodedPayloadManager;
import com.quorum.tessera.transaction.PrivacyHelper;
import com.quorum.tessera.transaction.ReceiveResponse;
import com.quorum.tessera.transaction.SendRequest;
import com.quorum.tessera.transaction.exception.RecipientKeyNotFoundException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class EncodedPayloadManagerImplTest {

  private Enclave enclave;

  private PrivacyHelper privacyHelper;

  private PayloadDigest payloadDigest;

  private EncodedPayloadManager encodedPayloadManager;

  @Before
  public void init() {
    this.enclave = mock(Enclave.class);
    this.privacyHelper = mock(PrivacyHelper.class);
    this.payloadDigest = mock(PayloadDigest.class);

    this.encodedPayloadManager =
        new EncodedPayloadManagerImpl(enclave, privacyHelper, payloadDigest);
  }

  @After
  public void after() {
    verifyNoMoreInteractions(enclave, privacyHelper, payloadDigest);
  }

  @Test
  public void createCallsEnclaveCorrectly() {
    final String testPayload = "test payload";

    final String senderKeyBase64 = "BULeR8JyUWhiuuCMU/HLA0Q5pzkYT+cHII3ZKBey3Bo=";
    final PublicKey sender = PublicKey.from(Base64.getDecoder().decode(senderKeyBase64));

    final String singleRecipientBase64 = "QfeDAys9MPDs2XHExtc84jKGHxZg/aj52DTh0vtA3Xc=";
    final PublicKey singleRecipient =
        PublicKey.from(Base64.getDecoder().decode(singleRecipientBase64));
    final List<PublicKey> recipients = List.of(singleRecipient);

    final SendRequest request = mock(SendRequest.class);
    // when(request.getPayload()).thenReturn(testPayload.getBytes());
    when(request.getSender()).thenReturn(sender);
    when(request.getExecHash()).thenReturn(new byte[0]);
    when(request.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(request.getRecipients()).thenReturn(recipients);

    final EncodedPayload sampleReturnPayload = mock(EncodedPayload.class);
    when(enclave.encryptPayload(any(), eq(sender), eq(List.of(singleRecipient, sender)), any()))
        .thenReturn(sampleReturnPayload);

    final EncodedPayload result = encodedPayloadManager.create(request);

    assertThat(result).isSameAs(sampleReturnPayload);

    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(Set.of());
    verify(privacyHelper)
        .validateSendRequest(
            PrivacyMode.STANDARD_PRIVATE, List.of(singleRecipient, sender), List.of(), Set.of());
    verify(enclave)
        .encryptPayload(
            eq(request.getPayload()), eq(sender), eq(List.of(singleRecipient, sender)), any());
    verify(enclave).getForwardingKeys();
  }

  @Test
  public void createDeduplicatesRecipients() {
    final String testPayload = "test payload";

    final String senderKeyBase64 = "BULeR8JyUWhiuuCMU/HLA0Q5pzkYT+cHII3ZKBey3Bo=";
    final PublicKey sender = PublicKey.from(Base64.getDecoder().decode(senderKeyBase64));

    final String singleRecipientBase64 = "QfeDAys9MPDs2XHExtc84jKGHxZg/aj52DTh0vtA3Xc=";
    final PublicKey singleRecipient =
        PublicKey.from(Base64.getDecoder().decode(singleRecipientBase64));
    final List<PublicKey> recipients =
        List.of(singleRecipient, sender, singleRecipient); // list the keys multiple times

    final SendRequest request = mock(SendRequest.class);
    when(request.getSender()).thenReturn(sender);
    when(request.getRecipients()).thenReturn(recipients);
    when(request.getPayload()).thenReturn(testPayload.getBytes());
    when(request.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);

    final EncodedPayload sampleReturnPayload = mock(EncodedPayload.class);
    when(enclave.encryptPayload(any(), eq(sender), eq(List.of(singleRecipient, sender)), any()))
        .thenReturn(sampleReturnPayload);

    final EncodedPayload resultingPayload = encodedPayloadManager.create(request);

    assertThat(resultingPayload).isSameAs(sampleReturnPayload);

    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(Set.of());
    verify(privacyHelper)
        .validateSendRequest(
            PrivacyMode.STANDARD_PRIVATE, List.of(singleRecipient, sender), List.of(), Set.of());
    verify(enclave)
        .encryptPayload(
            eq(request.getPayload()), eq(sender), eq(List.of(singleRecipient, sender)), any());
    verify(enclave).getForwardingKeys();
  }

  @Test
  public void decryptTransactionSucceeds() {

    final String senderKeyBase64 = "BULeR8JyUWhiuuCMU/HLA0Q5pzkYT+cHII3ZKBey3Bo=";
    final PublicKey sender = PublicKey.from(Base64.getDecoder().decode(senderKeyBase64));

    final String singleRecipientBase64 = "QfeDAys9MPDs2XHExtc84jKGHxZg/aj52DTh0vtA3Xc=";
    final PublicKey singleRecipient =
        PublicKey.from(Base64.getDecoder().decode(singleRecipientBase64));

    final EncodedPayload samplePayload = mock(EncodedPayload.class);
    when(samplePayload.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(samplePayload.getSenderKey()).thenReturn(sender);
    when(samplePayload.getExecHash()).thenReturn(new byte[0]);

    when(payloadDigest.digest(any())).thenReturn("test hash".getBytes());
    when(enclave.getPublicKeys()).thenReturn(Set.of(singleRecipient));
    when(enclave.unencryptTransaction(samplePayload, singleRecipient))
        .thenReturn("decrypted data".getBytes());

    final ReceiveResponse response = encodedPayloadManager.decrypt(samplePayload, null);

    assertThat(response.getUnencryptedTransactionData()).isEqualTo("decrypted data".getBytes());
    assertThat(response.getPrivacyMode()).isEqualTo(PrivacyMode.STANDARD_PRIVATE);
    assertThat(response.getAffectedTransactions()).isEmpty();
    assertThat(response.getExecHash()).isEmpty();

    verify(payloadDigest, times(2)).digest(any());
    verify(enclave).getPublicKeys();
    verify(enclave, times(2)).unencryptTransaction(samplePayload, singleRecipient);
  }

  @Test
  public void decryptHasNoMatchingKeys() {

    final String senderKeyBase64 = "BULeR8JyUWhiuuCMU/HLA0Q5pzkYT+cHII3ZKBey3Bo=";

    final String singleRecipientBase64 = "QfeDAys9MPDs2XHExtc84jKGHxZg/aj52DTh0vtA3Xc=";
    final PublicKey singleRecipient =
        PublicKey.from(Base64.getDecoder().decode(singleRecipientBase64));

    final EncodedPayload samplePayload = mock(EncodedPayload.class);

    when(payloadDigest.digest(any())).thenReturn("test hash".getBytes());
    when(enclave.getPublicKeys()).thenReturn(Set.of(singleRecipient));
    when(enclave.unencryptTransaction(any(), any()))
        .thenThrow(new EnclaveException("test exception"));

    final Throwable throwable =
        catchThrowable(() -> encodedPayloadManager.decrypt(samplePayload, null));

    assertThat(throwable)
        .isExactlyInstanceOf(RecipientKeyNotFoundException.class)
        .hasMessage("No suitable recipient keys found to decrypt payload for dGVzdCBoYXNo");

    verify(payloadDigest, times(2)).digest(any());
    verify(enclave).getPublicKeys();
    verify(enclave).unencryptTransaction(any(), any());
  }

  @Test
  public void decryptBadCipherText() {
    final String testPayload = "test payload";

    final String senderKeyBase64 = "BULeR8JyUWhiuuCMU/HLA0Q5pzkYT+cHII3ZKBey3Bo=";
    final PublicKey sender = PublicKey.from(Base64.getDecoder().decode(senderKeyBase64));

    final String singleRecipientBase64 = "QfeDAys9MPDs2XHExtc84jKGHxZg/aj52DTh0vtA3Xc=";
    final PublicKey singleRecipient =
        PublicKey.from(Base64.getDecoder().decode(singleRecipientBase64));
    final List<PublicKey> recipients = List.of(singleRecipient);

    final EncodedPayload samplePayload =
        EncodedPayload.Builder.create()
            .withSenderKey(sender)
            .withRecipientKeys(recipients)
            .withCipherText(testPayload.getBytes())
            .withPrivacyMode(PrivacyMode.STANDARD_PRIVATE)
            .withAffectedContractTransactions(Map.of())
            .withExecHash(new byte[0])
            .build();

    when(payloadDigest.digest(any())).thenReturn("test hash".getBytes());
    when(enclave.getPublicKeys()).thenReturn(Set.of());
    when(enclave.unencryptTransaction(any(), any()))
        .thenThrow(new EnclaveException("test exception"));

    final Throwable throwable =
        catchThrowable(() -> encodedPayloadManager.decrypt(samplePayload, singleRecipient));

    assertThat(throwable).isInstanceOf(EnclaveException.class).hasMessage("test exception");

    verify(payloadDigest).digest(any());
    verify(enclave).unencryptTransaction(samplePayload, singleRecipient);
  }
}
