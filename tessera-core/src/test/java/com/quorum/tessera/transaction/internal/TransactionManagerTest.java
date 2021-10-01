package com.quorum.tessera.transaction.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.quorum.tessera.data.*;
import com.quorum.tessera.enclave.*;
import com.quorum.tessera.encryption.EncryptorException;
import com.quorum.tessera.encryption.Nonce;
import com.quorum.tessera.encryption.PublicKey;
import com.quorum.tessera.transaction.*;
import com.quorum.tessera.transaction.exception.MandatoryRecipientsNotAvailableException;
import com.quorum.tessera.transaction.exception.PrivacyViolationException;
import com.quorum.tessera.transaction.exception.RecipientKeyNotFoundException;
import com.quorum.tessera.transaction.exception.TransactionNotFoundException;
import com.quorum.tessera.transaction.publish.BatchPayloadPublisher;
import com.quorum.tessera.transaction.resend.ResendManager;
import java.util.*;
import java.util.concurrent.Callable;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

@Ignore
public class TransactionManagerTest {

  private TransactionManager transactionManager;

  private EncryptedTransactionDAO encryptedTransactionDAO;

  private EncryptedRawTransactionDAO encryptedRawTransactionDAO;

  private ResendManager resendManager;

  private Enclave enclave;

  private PayloadDigest mockDigest;

  private PrivacyHelper privacyHelper;

  private BatchPayloadPublisher batchPayloadPublisher;

  private final EncodedPayloadCodec encodedPayloadCodec = EncodedPayloadCodec.UNSUPPORTED;

  @Before
  public void onSetUp() {
    enclave = mock(Enclave.class);
    encryptedTransactionDAO = mock(EncryptedTransactionDAO.class);
    encryptedRawTransactionDAO = mock(EncryptedRawTransactionDAO.class);
    resendManager = mock(ResendManager.class);
    privacyHelper = mock(PrivacyHelper.class);
    batchPayloadPublisher = mock(BatchPayloadPublisher.class);
    mockDigest = cipherText -> cipherText;

    transactionManager =
        new TransactionManagerImpl(
            encryptedTransactionDAO,
            batchPayloadPublisher,
            enclave,
            encryptedRawTransactionDAO,
            resendManager,
            privacyHelper,
            mockDigest);
  }

  @After
  public void onTearDown() {
    verifyNoMoreInteractions(enclave, resendManager, batchPayloadPublisher);
    verifyNoMoreInteractions(privacyHelper, encryptedTransactionDAO);
  }

  @Test
  public void send() {
    EncodedPayload encodedPayload = mock(EncodedPayload.class);
    when(encodedPayload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());

    when(enclave.encryptPayload(
            any(byte[].class), any(PublicKey.class), anyList(), any(PrivacyMetadata.class)))
        .thenReturn(encodedPayload);

    PublicKey sender = PublicKey.from("SENDER".getBytes());
    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    when(enclave.getPublicKeys()).thenReturn(Set.of(receiver));

    byte[] payload = Base64.getEncoder().encode("PAYLOAD".getBytes());

    SendRequest sendRequest = mock(SendRequest.class);
    when(sendRequest.getPayload()).thenReturn(payload);
    when(sendRequest.getSender()).thenReturn(sender);
    when(sendRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendRequest.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);

    SendResponse result = transactionManager.send(sendRequest);

    assertThat(result).isNotNull();
    assertThat(result.getTransactionHash().toString()).isEqualTo("Q0lQSEVSVEVYVA==");
    assertThat(result.getManagedParties()).containsExactly(receiver);

    verify(enclave)
        .encryptPayload(
            any(byte[].class), any(PublicKey.class), anyList(), any(PrivacyMetadata.class));

    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();
    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(anySet());
    verify(privacyHelper)
        .validateSendRequest(any(PrivacyMode.class), anyList(), anyList(), anySet());
  }

  @Test
  public void sendWithMandatoryRecipients() {

    EncodedPayload encodedPayload = mock(EncodedPayload.class);

    when(encodedPayload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());

    when(enclave.encryptPayload(
            any(byte[].class), any(PublicKey.class), anyList(), any(PrivacyMetadata.class)))
        .thenReturn(encodedPayload);

    PublicKey sender = PublicKey.from("SENDER".getBytes());
    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    when(enclave.getPublicKeys()).thenReturn(Set.of(receiver));

    byte[] payload = Base64.getEncoder().encode("PAYLOAD".getBytes());

    SendRequest sendRequest = mock(SendRequest.class);
    when(sendRequest.getPayload()).thenReturn(payload);
    when(sendRequest.getSender()).thenReturn(sender);
    when(sendRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendRequest.getPrivacyMode()).thenReturn(PrivacyMode.MANDATORY_RECIPIENTS);
    when(sendRequest.getMandatoryRecipients()).thenReturn(Set.of(receiver));

    ArgumentCaptor<PrivacyMetadata> metadataArgCaptor =
        ArgumentCaptor.forClass(PrivacyMetadata.class);

    SendResponse result = transactionManager.send(sendRequest);

    assertThat(result).isNotNull();
    assertThat(result.getTransactionHash().toString()).isEqualTo("Q0lQSEVSVEVYVA==");
    assertThat(result.getManagedParties()).containsExactly(receiver);

    verify(enclave)
        .encryptPayload(
            any(byte[].class), any(PublicKey.class), anyList(), metadataArgCaptor.capture());

    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();

    final PrivacyMetadata metadata = metadataArgCaptor.getValue();

    assertThat(metadata.getPrivacyMode()).isEqualTo(PrivacyMode.MANDATORY_RECIPIENTS);
    assertThat(metadata.getMandatoryRecipients()).containsExactly(receiver);

    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(anySet());
    verify(privacyHelper).findAffectedContractTransactionsFromPayload(encodedPayload);
    verify(privacyHelper)
        .validateSendRequest(any(PrivacyMode.class), anyList(), anyList(), anySet());
  }

  @Test
  public void sendAlsoWithPublishCallbackCoverage() {
    EncodedPayload encodedPayload = mock(EncodedPayload.class);

    when(encodedPayload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());

    when(enclave.encryptPayload(
            any(byte[].class), any(PublicKey.class), anyList(), any(PrivacyMetadata.class)))
        .thenReturn(encodedPayload);

    doAnswer(
            invocation -> {
              Callable callable = invocation.getArgument(1);
              callable.call();
              return mock(EncryptedTransaction.class);
            })
        .when(encryptedTransactionDAO)
        .save(any(EncryptedTransaction.class), any(Callable.class));

    PublicKey sender = PublicKey.from("SENDER".getBytes());
    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    byte[] payload = Base64.getEncoder().encode("PAYLOAD".getBytes());

    SendRequest sendRequest = mock(SendRequest.class);
    when(sendRequest.getPayload()).thenReturn(payload);
    when(sendRequest.getSender()).thenReturn(sender);
    when(sendRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendRequest.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);

    SendResponse result = transactionManager.send(sendRequest);

    assertThat(result).isNotNull();
    assertThat(result.getTransactionHash().toString()).isEqualTo("Q0lQSEVSVEVYVA==");
    assertThat(result.getManagedParties()).isEmpty();

    verify(enclave)
        .encryptPayload(
            any(byte[].class), any(PublicKey.class), anyList(), any(PrivacyMetadata.class));

    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();
    verify(batchPayloadPublisher).publishPayload(any(), anyList());

    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(anySet());
    verify(privacyHelper)
        .validateSendRequest(any(PrivacyMode.class), anyList(), anyList(), anySet());
  }

  @Test
  public void sendWithDuplicateRecipients() {
    EncodedPayload encodedPayload = mock(EncodedPayload.class);

    when(encodedPayload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());

    when(enclave.encryptPayload(
            any(byte[].class), any(PublicKey.class), anyList(), any(PrivacyMetadata.class)))
        .thenReturn(encodedPayload);

    when(enclave.getForwardingKeys()).thenReturn(Set.of(PublicKey.from("RECEIVER".getBytes())));

    PublicKey sender = PublicKey.from("SENDER".getBytes());
    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    byte[] payload = Base64.getEncoder().encode("PAYLOAD".getBytes());

    SendRequest sendRequest = mock(SendRequest.class);
    when(sendRequest.getPayload()).thenReturn(payload);
    when(sendRequest.getSender()).thenReturn(sender);
    when(sendRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendRequest.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);

    SendResponse result = transactionManager.send(sendRequest);

    assertThat(result).isNotNull();

    assertThat(Base64.getEncoder().encodeToString(result.getTransactionHash().getHashBytes()))
        .isEqualTo("Q0lQSEVSVEVYVA==");
    assertThat(result.getManagedParties()).isEmpty();

    verify(enclave)
        .encryptPayload(
            any(byte[].class), any(PublicKey.class), anyList(), any(PrivacyMetadata.class));

    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();

    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(anySet());
    verify(privacyHelper)
        .validateSendRequest(any(PrivacyMode.class), anyList(), anyList(), anySet());
  }

  @Test
  public void sendSignedTransaction() {

    EncodedPayload payload = mock(EncodedPayload.class);

    when(payload.getCipherText()).thenReturn("ENCRYPTED_PAYLOAD".getBytes());

    MessageHash messageHash = mock(MessageHash.class);
    when(messageHash.getHashBytes()).thenReturn("HASH".getBytes());

    EncryptedRawTransaction encryptedRawTransaction = mock(EncryptedRawTransaction.class);
    when(encryptedRawTransaction.getHash()).thenReturn(messageHash);

    when(encryptedRawTransaction.toRawTransaction()).thenReturn(mock(RawTransaction.class));

    when(encryptedRawTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(encryptedRawTransaction));

    when(enclave.encryptPayload(any(RawTransaction.class), anyList(), any(PrivacyMetadata.class)))
        .thenReturn(payload);

    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    when(enclave.getPublicKeys()).thenReturn(Set.of(receiver));

    SendSignedRequest sendSignedRequest = mock(SendSignedRequest.class);
    when(sendSignedRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendSignedRequest.getSignedData()).thenReturn("HASH".getBytes());
    when(sendSignedRequest.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);

    SendResponse result = transactionManager.sendSignedTransaction(sendSignedRequest);

    assertThat(result).isNotNull();
    assertThat(result.getTransactionHash()).isEqualTo(new MessageHash("HASH".getBytes()));
    assertThat(result.getManagedParties()).containsExactly(receiver);

    ArgumentCaptor<PrivacyMetadata> data = ArgumentCaptor.forClass(PrivacyMetadata.class);

    verify(enclave).encryptPayload(any(RawTransaction.class), anyList(), data.capture());
    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(encryptedRawTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();

    final PrivacyMetadata passingData = data.getValue();
    assertThat(passingData.getPrivacyMode()).isEqualTo(PrivacyMode.STANDARD_PRIVATE);
    assertThat(passingData.getPrivacyGroupId()).isNotPresent();

    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(anySet());
    verify(privacyHelper)
        .validateSendRequest(any(PrivacyMode.class), anyList(), anyList(), anySet());
  }

  @Test
  public void sendSignedTransactionWithMandatoryRecipients() {

    EncodedPayload payload = mock(EncodedPayload.class);

    RawTransaction rawTransaction = mock(RawTransaction.class);
    EncryptedRawTransaction encryptedRawTransaction = mock(EncryptedRawTransaction.class);
    when(encryptedRawTransaction.getSender()).thenReturn("SENDER".getBytes());
    when(encryptedRawTransaction.toRawTransaction()).thenReturn(rawTransaction);

    when(encryptedRawTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(encryptedRawTransaction));

    when(payload.getCipherText()).thenReturn("ENCRYPTED_PAYLOAD".getBytes());

    when(enclave.encryptPayload(any(RawTransaction.class), anyList(), any(PrivacyMetadata.class)))
        .thenReturn(payload);

    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    when(enclave.getPublicKeys()).thenReturn(Set.of(receiver));

    SendSignedRequest sendSignedRequest = mock(SendSignedRequest.class);
    when(sendSignedRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendSignedRequest.getSignedData()).thenReturn("HASH".getBytes());
    when(sendSignedRequest.getPrivacyMode()).thenReturn(PrivacyMode.MANDATORY_RECIPIENTS);
    when(sendSignedRequest.getMandatoryRecipients()).thenReturn(Set.of(receiver));

    SendResponse result = transactionManager.sendSignedTransaction(sendSignedRequest);

    assertThat(result).isNotNull();
    assertThat(result.getTransactionHash()).isEqualTo(new MessageHash("HASH".getBytes()));
    assertThat(result.getManagedParties()).containsExactly(receiver);

    ArgumentCaptor<PrivacyMetadata> data = ArgumentCaptor.forClass(PrivacyMetadata.class);

    verify(enclave).encryptPayload(any(RawTransaction.class), anyList(), data.capture());
    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(encryptedRawTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();

    final PrivacyMetadata passingData = data.getValue();
    assertThat(passingData.getPrivacyMode()).isEqualTo(PrivacyMode.MANDATORY_RECIPIENTS);
    assertThat(passingData.getPrivacyGroupId()).isNotPresent();
    assertThat(passingData.getMandatoryRecipients()).containsExactly(receiver);

    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(anySet());
    verify(privacyHelper)
        .validateSendRequest(any(PrivacyMode.class), anyList(), anyList(), anySet());
  }

  @Test
  public void sendSignedTransactionWithCallbackCoverage() {

    EncodedPayload payload = mock(EncodedPayload.class);

    EncryptedRawTransaction encryptedRawTransaction = mock(EncryptedRawTransaction.class);
    when(encryptedRawTransaction.getSender()).thenReturn("SENDER".getBytes());

    RawTransaction rawTransaction = mock(RawTransaction.class);
    when(encryptedRawTransaction.toRawTransaction()).thenReturn(rawTransaction);

    when(encryptedRawTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(encryptedRawTransaction));

    doAnswer(
            invocation -> {
              Callable callable = invocation.getArgument(1);
              callable.call();
              return mock(EncryptedTransaction.class);
            })
        .when(encryptedTransactionDAO)
        .save(any(EncryptedTransaction.class), any(Callable.class));

    when(payload.getCipherText()).thenReturn("ENCRYPTED_PAYLOAD".getBytes());

    when(enclave.encryptPayload(any(RawTransaction.class), anyList(), any(PrivacyMetadata.class)))
        .thenReturn(payload);

    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    SendSignedRequest sendSignedRequest = mock(SendSignedRequest.class);
    when(sendSignedRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendSignedRequest.getSignedData()).thenReturn("HASH".getBytes());
    when(sendSignedRequest.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(sendSignedRequest.getAffectedContractTransactions()).thenReturn(Set.of());
    when(sendSignedRequest.getExecHash()).thenReturn("execHash".getBytes());
    when(sendSignedRequest.getPrivacyGroupId())
        .thenReturn(Optional.of(PrivacyGroup.Id.fromBytes("group".getBytes())));

    SendResponse result = transactionManager.sendSignedTransaction(sendSignedRequest);

    assertThat(result).isNotNull();
    assertThat(result.getTransactionHash()).isEqualTo(new MessageHash("HASH".getBytes()));
    assertThat(result.getManagedParties()).isEmpty();

    ArgumentCaptor<PrivacyMetadata> data = ArgumentCaptor.forClass(PrivacyMetadata.class);

    verify(enclave).encryptPayload(any(RawTransaction.class), anyList(), data.capture());
    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(encryptedRawTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();
    verify(batchPayloadPublisher).publishPayload(any(), anyList());

    final PrivacyMetadata passingData = data.getValue();
    assertThat(passingData.getPrivacyMode()).isEqualTo(PrivacyMode.PRIVATE_STATE_VALIDATION);
    assertThat(passingData.getAffectedContractTransactions()).isEmpty();
    assertThat(passingData.getExecHash()).isEqualTo("execHash".getBytes());
    assertThat(passingData.getPrivacyGroupId())
        .isPresent()
        .get()
        .isEqualTo(PrivacyGroup.Id.fromBytes("group".getBytes()));
    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(anySet());
    verify(privacyHelper)
        .validateSendRequest(any(PrivacyMode.class), anyList(), anyList(), anySet());
  }

  @Test
  public void sendSignedTransactionWithDuplicateRecipients() {
    EncodedPayload payload = mock(EncodedPayload.class);

    EncryptedRawTransaction encryptedRawTransaction = mock(EncryptedRawTransaction.class);
    when(encryptedRawTransaction.getSender()).thenReturn("SENDER".getBytes());

    RawTransaction rawTransaction = mock(RawTransaction.class);
    when(encryptedRawTransaction.toRawTransaction()).thenReturn(rawTransaction);

    when(encryptedRawTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(encryptedRawTransaction));

    when(payload.getCipherText()).thenReturn("ENCRYPTED_PAYLOAD".getBytes());
    when(enclave.getForwardingKeys()).thenReturn(Set.of(PublicKey.from("RECEIVER".getBytes())));
    when(enclave.encryptPayload(any(RawTransaction.class), anyList(), any(PrivacyMetadata.class)))
        .thenReturn(payload);

    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    SendSignedRequest sendSignedRequest = mock(SendSignedRequest.class);
    when(sendSignedRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendSignedRequest.getSignedData()).thenReturn("HASH".getBytes());
    when(sendSignedRequest.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);

    SendResponse result = transactionManager.sendSignedTransaction(sendSignedRequest);

    assertThat(result).isNotNull();
    assertThat(result.getTransactionHash()).isEqualTo(new MessageHash("HASH".getBytes()));
    assertThat(result.getManagedParties()).isEmpty();

    verify(enclave)
        .encryptPayload(any(RawTransaction.class), anyList(), any(PrivacyMetadata.class));
    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(encryptedRawTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();

    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(anySet());
    verify(privacyHelper)
        .validateSendRequest(any(PrivacyMode.class), anyList(), anyList(), anySet());
  }

  @Test
  public void sendSignedTransactionNoRawTransactionFoundException() {

    when(encryptedRawTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.empty());

    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());
    SendSignedRequest sendSignedRequest = mock(SendSignedRequest.class);
    when(sendSignedRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendSignedRequest.getSignedData()).thenReturn("HASH".getBytes());

    try {
      transactionManager.sendSignedTransaction(sendSignedRequest);
      failBecauseExceptionWasNotThrown(TransactionNotFoundException.class);
    } catch (TransactionNotFoundException ex) {
      verify(encryptedRawTransactionDAO).retrieveByHash(any(MessageHash.class));
    }
  }

  @Test
  public void delete() {

    MessageHash messageHash = mock(MessageHash.class);

    transactionManager.delete(messageHash);

    verify(encryptedTransactionDAO).delete(messageHash);
  }

  @Test
  public void storePayloadAsRecipient() {
    EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.empty());

    transactionManager.storePayload(payload);

    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class));
    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());

    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(anySet());
    verify(privacyHelper)
        .validateSendRequest(any(PrivacyMode.class), anyList(), anyList(), anySet());
  }

  @Test
  public void storePayloadWhenWeAreSender() {
    final PublicKey senderKey = PublicKey.from("SENDER".getBytes());

    final EncodedPayload encodedPayload = mock(EncodedPayload.class);
    when(encodedPayload.getSenderKey()).thenReturn(senderKey);
    when(encodedPayload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(encodedPayload.getRecipientBoxes()).thenReturn(List.of());
    when(encodedPayload.getRecipientKeys()).thenReturn(List.of());

    when(enclave.getPublicKeys()).thenReturn(Set.of(senderKey));

    when(privacyHelper.validatePayload(any(TxHash.class), any(EncodedPayload.class), anyList()))
        .thenReturn(true);

    transactionManager.storePayload(encodedPayload);

    verify(resendManager).acceptOwnMessage(encodedPayload);
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());

    verify(privacyHelper).validatePayload(any(TxHash.class), any(EncodedPayload.class), anyList());
    verify(privacyHelper).findAffectedContractTransactionsFromPayload(encodedPayload);
  }

  @Test
  public void storePayloadWhenWeAreSenderWithPrivateStateConsensus() {
    final PublicKey senderKey = PublicKey.from("SENDER".getBytes());

    final EncodedPayload encodedPayload = mock(EncodedPayload.class);
    when(encodedPayload.getSenderKey()).thenReturn(senderKey);
    when(encodedPayload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(encodedPayload.getCipherTextNonce()).thenReturn(null);
    when(encodedPayload.getRecipientBoxes()).thenReturn(List.of());
    when(encodedPayload.getRecipientNonce()).thenReturn(null);
    when(encodedPayload.getRecipientKeys()).thenReturn(List.of());
    when(encodedPayload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(encodedPayload.getAffectedContractTransactions()).thenReturn(Map.of());
    when(encodedPayload.getExecHash()).thenReturn(new byte[0]);

    when(enclave.getPublicKeys()).thenReturn(Set.of(senderKey));

    transactionManager.storePayload(encodedPayload);

    verify(resendManager).acceptOwnMessage(encodedPayload);

    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(), any());
  }

  @Test
  public void storePayloadAsRecipientWithPrivateStateConsensus() {
    EncodedPayload payload = mock(EncodedPayload.class);

    when(payload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.empty());

    transactionManager.storePayload(payload);

    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class));
    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(), any());

    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(anySet());
    verify(privacyHelper)
        .validateSendRequest(any(PrivacyMode.class), anyList(), anyList(), anySet());
  }

  @Test
  public void storePayloadAsRecipientWithAffectedContractTxsButPsvFlagMismatched() {

    final byte[] input = "SOMEDATA".getBytes();
    final byte[] affectedContractPayload = "SOMEOTHERDATA".getBytes();
    final PublicKey senderKey = PublicKey.from("sender".getBytes());

    final EncodedPayload payload = mock(EncodedPayload.class);
    final EncryptedTransaction affectedContractTx = mock(EncryptedTransaction.class);
    final EncodedPayload affectedContractEncodedPayload = mock(EncodedPayload.class);

    Map<TxHash, SecurityHash> affectedContractTransactionHashes = new HashMap<>();
    affectedContractTransactionHashes.put(
        new TxHash(
            "bfMIqWJ/QGQhkK4USxMBxduzfgo/SIGoCros5bWYfPKUBinlAUCqLVOUAP9q+BgLlsWni1M6rnzfmaqSw2J5hQ=="),
        SecurityHash.from("securityHash".getBytes()));

    when(affectedContractTx.getEncodedPayload()).thenReturn(input);
    when(affectedContractTx.getHash())
        .thenReturn(
            new MessageHash(
                new TxHash(
                        "bfMIqWJ/QGQhkK4USxMBxduzfgo/SIGoCros5bWYfPKUBinlAUCqLVOUAP9q+BgLlsWni1M6rnzfmaqSw2J5hQ==")
                    .getBytes()));
    when(payload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(affectedContractEncodedPayload.getPrivacyMode())
        .thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(payload.getAffectedContractTransactions()).thenReturn(affectedContractTransactionHashes);
    when(payload.getSenderKey()).thenReturn(senderKey);
    when(affectedContractEncodedPayload.getRecipientKeys()).thenReturn(List.of(senderKey));

    when(encryptedTransactionDAO.findByHashes(any())).thenReturn(List.of(affectedContractTx));
    when(affectedContractTx.getEncodedPayload()).thenReturn(affectedContractPayload);

    transactionManager.storePayload(payload);
    // Ignore transaction - not save
    verify(encryptedTransactionDAO).findByHashes(any());
    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(anySet());
    verify(privacyHelper)
        .validateSendRequest(any(PrivacyMode.class), anyList(), anyList(), anySet());
  }

  @Test
  public void storePayloadSenderNotGenuineACOTHNotFound() {
    final byte[] input = "SOMEDATA".getBytes();
    final byte[] affectedContractPayload = "SOMEOTHERDATA".getBytes();
    final PublicKey senderKey = PublicKey.from("sender".getBytes());

    final EncodedPayload payload = mock(EncodedPayload.class);
    final EncryptedTransaction affectedContractTx = mock(EncryptedTransaction.class);
    final EncodedPayload affectedContractEncodedPayload = mock(EncodedPayload.class);

    final TxHash txHash =
        new TxHash(
            "bfMIqWJ/QGQhkK4USxMBxduzfgo/SIGoCros5bWYfPKUBinlAUCqLVOUAP9q+BgLlsWni1M6rnzfmaqSw2J5hQ==");

    Map<TxHash, SecurityHash> affectedContractTransactionHashes = new HashMap<>();
    affectedContractTransactionHashes.put(txHash, SecurityHash.from("securityHash".getBytes()));
    affectedContractTransactionHashes.put(
        new TxHash(
            "bfMIqWJ/QGQhkK4USxMBxduzfgo/SIGoCros5bWYfPKUBinlAUCqLVOUAP9q+BgLlsWni1M6rnzfmaqSr5J5hQ=="),
        SecurityHash.from("bogus".getBytes()));

    when(affectedContractTx.getEncodedPayload()).thenReturn(input);
    when(payload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(affectedContractEncodedPayload.getPrivacyMode())
        .thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(payload.getAffectedContractTransactions()).thenReturn(affectedContractTransactionHashes);
    when(payload.getSenderKey()).thenReturn(senderKey);
    when(affectedContractEncodedPayload.getRecipientKeys()).thenReturn(Arrays.asList(senderKey));

    when(encryptedTransactionDAO.findByHashes(List.of(new MessageHash(txHash.getBytes()))))
        .thenReturn(List.of(affectedContractTx));
    when(affectedContractTx.getEncodedPayload()).thenReturn(affectedContractPayload);

    transactionManager.storePayload(payload);
    // Ignore transaction - not save
    verify(encryptedTransactionDAO, times(0)).save(any(EncryptedTransaction.class));
    verify(encryptedTransactionDAO).findByHashes(any());

    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(anySet());
    verify(privacyHelper)
        .validateSendRequest(any(PrivacyMode.class), anyList(), anyList(), anySet());
  }

  @Test
  public void storePayloadSenderNotInRecipientList() {
    final byte[] input = "SOMEDATA".getBytes();
    final byte[] affectedContractPayload = "SOMEOTHERDATA".getBytes();
    final PublicKey senderKey = PublicKey.from("sender".getBytes());
    final PublicKey someOtherKey = PublicKey.from("otherKey".getBytes());

    final EncodedPayload payload = mock(EncodedPayload.class);
    final EncryptedTransaction affectedContractTx = mock(EncryptedTransaction.class);
    final EncodedPayload affectedContractEncodedPayload = mock(EncodedPayload.class);

    final TxHash txHash =
        new TxHash(
            "bfMIqWJ/QGQhkK4USxMBxduzfgo/SIGoCros5bWYfPKUBinlAUCqLVOUAP9q+BgLlsWni1M6rnzfmaqSw2J5hQ==");

    Map<TxHash, SecurityHash> affectedContractTransactionHashes = new HashMap<>();
    affectedContractTransactionHashes.put(txHash, SecurityHash.from("securityHash".getBytes()));
    affectedContractTransactionHashes.put(
        new TxHash(
            "bfMIqWJ/QGQhkK4USxMBxduzfgo/SIGoCros5bWYfPKUBinlAUCqLVOUAP9q+BgLlsWni1M6rnzfmaqSr5J5hQ=="),
        SecurityHash.from("bogus".getBytes()));

    when(affectedContractTx.getEncodedPayload()).thenReturn(input);
    when(affectedContractTx.getHash()).thenReturn(new MessageHash(txHash.getBytes()));
    when(payload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(affectedContractEncodedPayload.getPrivacyMode())
        .thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(payload.getAffectedContractTransactions()).thenReturn(affectedContractTransactionHashes);
    when(payload.getSenderKey()).thenReturn(senderKey);
    when(affectedContractEncodedPayload.getRecipientKeys()).thenReturn(Arrays.asList(someOtherKey));

    when(encryptedTransactionDAO.findByHashes(any())).thenReturn(List.of(affectedContractTx));
    when(affectedContractTx.getEncodedPayload()).thenReturn(affectedContractPayload);

    transactionManager.storePayload(payload);
    // Ignore transaction - not save
    verify(encryptedTransactionDAO).findByHashes(any());

    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(anySet());
    verify(privacyHelper)
        .validateSendRequest(any(PrivacyMode.class), anyList(), anyList(), anySet());
  }

  @Test
  public void storePayloadPsvWithInvalidSecurityHashes() {

    EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);

    when(enclave.findInvalidSecurityHashes(any(), any()))
        .thenReturn(Set.of(new TxHash("invalidHash".getBytes())));

    assertThatExceptionOfType(PrivacyViolationException.class)
        .describedAs("There are privacy violation for psv")
        .isThrownBy(() -> transactionManager.storePayload(payload))
        .withMessageContaining("Invalid security hashes identified for PSC TX");

    verify(enclave).findInvalidSecurityHashes(any(), any());

    verify(privacyHelper).findAffectedContractTransactionsFromSendRequest(anySet());
    verify(privacyHelper)
        .validateSendRequest(any(PrivacyMode.class), anyList(), anyList(), anySet());
  }

  @Test
  public void storePayloadWithInvalidSecurityHashesIgnoreIfNotPsv() {

    final PublicKey senderKey = mock(PublicKey.class);

    TxHash txHash = mock(TxHash.class);
    when(txHash.getBytes()).thenReturn("invalidHash".getBytes());

    SecurityHash securityHash = mock(SecurityHash.class);
    when(securityHash.getData()).thenReturn("security".getBytes());

    final Map<TxHash, SecurityHash> affectedTx = Map.of(txHash, securityHash);

    final EncodedPayload initialPayload = mock(EncodedPayload.class);
    when(initialPayload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.empty());

    Set<TxHash> invalidSecurityHashes = Set.of(txHash);

    when(enclave.findInvalidSecurityHashes(any(EncodedPayload.class), anyList()))
        .thenReturn(invalidSecurityHashes);

    EncodedPayload sanitisedPayload = mock(EncodedPayload.class);
    when(sanitisedPayload.getSenderKey()).thenReturn(senderKey);
    when(sanitisedPayload.getAffectedContractTransactions()).thenReturn(affectedTx);

    when(privacyHelper.sanitisePrivacyPayload(
            any(TxHash.class), any(EncodedPayload.class), anySet()))
        .thenReturn(sanitisedPayload);

    when(privacyHelper.validatePayload(any(TxHash.class), any(EncodedPayload.class), anyList()))
        .thenReturn(true);

    transactionManager.storePayload(initialPayload);

    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class));
    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(), any());

    verify(privacyHelper).findAffectedContractTransactionsFromPayload(initialPayload);
    verify(privacyHelper).validatePayload(any(TxHash.class), any(EncodedPayload.class), anyList());
    verify(privacyHelper)
        .sanitisePrivacyPayload(any(TxHash.class), any(EncodedPayload.class), anySet());
  }

  @Test
  public void storePayloadWithExistingRecipientAndMismatchedContents() {
    EncryptedTransaction existingDatabaseEntry = new EncryptedTransaction();
    existingDatabaseEntry.setHash(new MessageHash(new byte[0]));

    EncodedPayload existingPayload = mock(EncodedPayload.class);
    when(existingPayload.getCipherText()).thenReturn("ct1".getBytes());

    existingDatabaseEntry.setPayload(existingPayload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(existingDatabaseEntry));

    EncodedPayload payloadToStore =
        EncodedPayload.Builder.create().withCipherText("ct2".getBytes()).build();

    final Throwable throwable =
        catchThrowable(() -> transactionManager.storePayload(payloadToStore));

    assertThat(throwable)
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Invalid existing transaction");

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());
  }

  @Test
  public void storePayloadWithExistingRecipientPSVRecipientNotFound() {
    EncryptedTransaction existingDatabaseEntry = new EncryptedTransaction();
    existingDatabaseEntry.setHash(new MessageHash(new byte[0]));

    EncodedPayload existingPayload = mock(EncodedPayload.class);
    when(existingPayload.getCipherText()).thenReturn("ct1".getBytes());
    when(existingPayload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(existingPayload.getExecHash()).thenReturn("execHash".getBytes());

    existingDatabaseEntry.setPayload(existingPayload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(existingDatabaseEntry));

    EncodedPayload payloadToStore = mock(EncodedPayload.class);
    when(payloadToStore.getCipherText()).thenReturn("ct1".getBytes());
    when(payloadToStore.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(payloadToStore.getExecHash()).thenReturn("execHash".getBytes());
    when(payloadToStore.getRecipientKeys())
        .thenReturn(List.of(PublicKey.from("recipient1".getBytes())));
    when(payloadToStore.getRecipientBoxes())
        .thenReturn(List.of(RecipientBox.from("recipient_box1".getBytes())));

    final Throwable throwable =
        catchThrowable(() -> transactionManager.storePayload(payloadToStore));

    assertThat(throwable)
        .isInstanceOf(RuntimeException.class)
        .hasMessage("expected recipient not found");

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());
  }

  @Test
  public void storePayloadWithExistingRecipientPSV() {
    privacyHelper = mock(PrivacyHelper.class);
    transactionManager =
        new TransactionManagerImpl(
            encryptedTransactionDAO,
            batchPayloadPublisher,
            enclave,
            encryptedRawTransactionDAO,
            resendManager,
            privacyHelper,
            mockDigest);

    when(privacyHelper.validatePayload(any(), any(), any())).thenReturn(true);

    PublicKey recipient1 = PublicKey.from("recipient1".getBytes());
    PublicKey recipient2 = PublicKey.from("recipient2".getBytes());

    EncryptedTransaction existingDatabaseEntry = new EncryptedTransaction();
    existingDatabaseEntry.setHash(new MessageHash(new byte[0]));

    EncodedPayload existingPayload =
        EncodedPayload.Builder.create()
            .withCipherText("ct1".getBytes())
            .withPrivacyMode(PrivacyMode.PRIVATE_STATE_VALIDATION)
            .withAffectedContractTransactions(Map.of(TxHash.from(new byte[0]), new byte[0]))
            .withExecHash("execHash".getBytes())
            .withRecipientKeys(List.of(recipient1, recipient2))
            .build();

    existingDatabaseEntry.setPayload(existingPayload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(existingDatabaseEntry));

    EncodedPayload payloadToStore =
        EncodedPayload.Builder.create()
            .withCipherText("ct1".getBytes())
            .withPrivacyMode(PrivacyMode.PRIVATE_STATE_VALIDATION)
            .withAffectedContractTransactions(Map.of(TxHash.from(new byte[0]), new byte[0]))
            .withExecHash("execHash".getBytes())
            .withRecipientKeys(List.of(recipient1, recipient2))
            .withRecipientBox("recipient_box1".getBytes())
            .build();

    MessageHash response = transactionManager.storePayload(payloadToStore);

    assertThat(response.toString()).isEqualTo("Y3Qx");

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(encryptedTransactionDAO).update(existingDatabaseEntry);
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());
  }

  @Test
  public void storePayloadWithExistingRecipientNonPSV() {
    PublicKey recipient1 = PublicKey.from("recipient1".getBytes());
    PublicKey recipient2 = PublicKey.from("recipient2".getBytes());

    EncryptedTransaction existingDatabaseEntry = new EncryptedTransaction();
    existingDatabaseEntry.setHash(new MessageHash(new byte[0]));

    EncodedPayload existingPayload = mock(EncodedPayload.class);
    when(existingPayload.getCipherText()).thenReturn("ct1".getBytes());
    when(existingPayload.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(existingPayload.getRecipientKeys()).thenReturn(List.of(recipient1));
    when(existingPayload.getRecipientBoxes())
        .thenReturn(List.of(RecipientBox.from("recipient_box1".getBytes())));

    existingDatabaseEntry.setPayload(existingPayload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(existingDatabaseEntry));

    EncodedPayload payloadToStore = mock(EncodedPayload.class);
    when(payloadToStore.getCipherText()).thenReturn("ct1".getBytes());
    when(payloadToStore.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(payloadToStore.getRecipientKeys()).thenReturn(List.of(recipient2));
    when(payloadToStore.getRecipientBoxes())
        .thenReturn(List.of(RecipientBox.from("recipient_box2".getBytes())));

    MessageHash response = transactionManager.storePayload(payloadToStore);

    assertThat(response.toString()).isEqualTo("Y3Qx");

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(encryptedTransactionDAO).update(existingDatabaseEntry);
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());
  }

  @Test
  public void storePayloadWithDuplicateExistingRecipient() {
    PublicKey recipient1 = PublicKey.from("recipient1".getBytes());

    EncryptedTransaction existingDatabaseEntry = new EncryptedTransaction();
    existingDatabaseEntry.setHash(new MessageHash(new byte[0]));

    EncodedPayload existingPayload =
        EncodedPayload.Builder.create()
            .withCipherText("ct1".getBytes())
            .withPrivacyMode(PrivacyMode.STANDARD_PRIVATE)
            .withRecipientKeys(List.of(recipient1))
            .withRecipientBox("recipient_box1".getBytes())
            .build();

    existingDatabaseEntry.setPayload(existingPayload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(existingDatabaseEntry));

    EncodedPayload payloadToStore =
        EncodedPayload.Builder.create()
            .withCipherText("ct1".getBytes())
            .withPrivacyMode(PrivacyMode.STANDARD_PRIVATE)
            .withRecipientKeys(List.of(recipient1))
            .withRecipientBox("recipient_box1".getBytes())
            .build();

    MessageHash response = transactionManager.storePayload(payloadToStore);

    assertThat(response.toString()).isEqualTo("Y3Qx");

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());
  }

  @Test
  public void storePayloadWithExistingRecipientLegacyNoRecipients() {
    EncryptedTransaction existingDatabaseEntry = new EncryptedTransaction();
    existingDatabaseEntry.setHash(new MessageHash(new byte[0]));

    EncodedPayload existingPayload =
        EncodedPayload.Builder.create()
            .withCipherText("ct1".getBytes())
            .withPrivacyMode(PrivacyMode.STANDARD_PRIVATE)
            .withRecipientBox("recipient_box1".getBytes())
            .build();

    existingDatabaseEntry.setPayload(existingPayload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(existingDatabaseEntry));

    EncodedPayload payloadToStore =
        EncodedPayload.Builder.create()
            .withCipherText("ct1".getBytes())
            .withPrivacyMode(PrivacyMode.STANDARD_PRIVATE)
            .withRecipientBox("recipient_box2".getBytes())
            .build();

    MessageHash response = transactionManager.storePayload(payloadToStore);

    assertThat(response.toString()).isEqualTo("Y3Qx");

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(encryptedTransactionDAO).update(existingDatabaseEntry);
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());
  }

  @Test
  public void receive() {
    PublicKey sender = PublicKey.from("sender".getBytes());
    byte[] randomData = Base64.getEncoder().encode("odd-data".getBytes());
    MessageHash messageHash = new MessageHash(randomData);

    ReceiveRequest receiveRequest =
        ReceiveRequest.Builder.create()
            .withRecipient(mock(PublicKey.class))
            .withTransactionHash(messageHash)
            .build();

    EncryptedTransaction encryptedTransaction = new EncryptedTransaction();
    encryptedTransaction.setHash(messageHash);
    encryptedTransaction.setEncodedPayload(randomData);
    encryptedTransaction.setEncodedPayloadCodec(EncodedPayloadCodec.LEGACY);

    EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getExecHash()).thenReturn("execHash".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(payload.getSenderKey()).thenReturn(sender);

    encryptedTransaction.setPayload(payload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(encryptedTransaction));

    byte[] expectedOutcome = "Encrypted payload".getBytes();

    when(enclave.unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class)))
        .thenReturn(expectedOutcome);

    PublicKey publicKey = mock(PublicKey.class);
    when(enclave.getPublicKeys()).thenReturn(Set.of(publicKey));

    ReceiveResponse receiveResponse = transactionManager.receive(receiveRequest);

    assertThat(receiveResponse).isNotNull();
    assertThat(receiveResponse.sender()).isEqualTo(sender);
    assertThat(receiveResponse.getUnencryptedTransactionData()).isEqualTo(expectedOutcome);
    assertThat(receiveResponse.getPrivacyGroupId()).isNotPresent();

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave, times(2)).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    verify(enclave).getPublicKeys();
  }

  @Test
  public void receiveWithPrivacyGroupId() {
    PublicKey sender = PublicKey.from("sender".getBytes());

    MessageHash messageHash = mock(MessageHash.class);
    when(messageHash.getHashBytes()).thenReturn("odd-data".getBytes());

    ReceiveRequest receiveRequest =
        ReceiveRequest.Builder.create()
            .withRecipient(mock(PublicKey.class))
            .withTransactionHash(messageHash)
            .build();

    EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getExecHash()).thenReturn("execHash".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(payload.getSenderKey()).thenReturn(sender);
    when(payload.getPrivacyGroupId())
        .thenReturn(Optional.of(PrivacyGroup.Id.fromBytes("group".getBytes())));

    EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    when(encryptedTransaction.getHash()).thenReturn(messageHash);
    when(encryptedTransaction.getPayload()).thenReturn(payload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(encryptedTransaction));

    byte[] expectedOutcome = "Encrypted payload".getBytes();

    when(enclave.unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class)))
        .thenReturn(expectedOutcome);

    PublicKey publicKey = mock(PublicKey.class);
    when(enclave.getPublicKeys()).thenReturn(Set.of(publicKey));

    ReceiveResponse receiveResponse = transactionManager.receive(receiveRequest);

    assertThat(receiveResponse).isNotNull();
    assertThat(receiveResponse.sender()).isEqualTo(sender);
    assertThat(receiveResponse.getUnencryptedTransactionData()).isEqualTo(expectedOutcome);
    assertThat(receiveResponse.getPrivacyGroupId()).isPresent();
    assertThat(receiveResponse.getPrivacyGroupId().get())
        .isEqualTo(PrivacyGroup.Id.fromBytes("group".getBytes()));

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave, times(2)).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    verify(enclave).getPublicKeys();
  }

  @Test
  public void receiveWithMandatoryRecipients() {
    PublicKey sender = PublicKey.from("sender".getBytes());
    byte[] randomData = Base64.getEncoder().encode("odd-data".getBytes());
    MessageHash messageHash = new MessageHash(randomData);

    ReceiveRequest receiveRequest =
        ReceiveRequest.Builder.create()
            .withRecipient(mock(PublicKey.class))
            .withTransactionHash(messageHash)
            .build();

    EncryptedTransaction encryptedTransaction = new EncryptedTransaction();
    encryptedTransaction.setHash(messageHash);

    PublicKey mandReceiver1 = PublicKey.from("mandatory1".getBytes());
    PublicKey mandReceiver2 = PublicKey.from("mandatory2".getBytes());
    EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getMandatoryRecipients()).thenReturn(Set.of(mandReceiver1, mandReceiver2));
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.MANDATORY_RECIPIENTS);
    when(payload.getSenderKey()).thenReturn(sender);
    when(payload.getPrivacyGroupId())
        .thenReturn(Optional.of(PrivacyGroup.Id.fromBytes("group".getBytes())));

    encryptedTransaction.setPayload(payload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(encryptedTransaction));

    byte[] expectedOutcome = "Encrypted payload".getBytes();

    when(enclave.unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class)))
        .thenReturn(expectedOutcome);

    PublicKey publicKey = mock(PublicKey.class);
    when(enclave.getPublicKeys()).thenReturn(Set.of(publicKey));

    ReceiveResponse receiveResponse = transactionManager.receive(receiveRequest);

    assertThat(receiveResponse).isNotNull();
    assertThat(receiveResponse.sender()).isEqualTo(sender);
    assertThat(receiveResponse.getUnencryptedTransactionData()).isEqualTo(expectedOutcome);
    assertThat(receiveResponse.getPrivacyGroupId()).isPresent();
    assertThat(receiveResponse.getPrivacyGroupId().get())
        .isEqualTo(PrivacyGroup.Id.fromBytes("group".getBytes()));

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave, times(2)).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    verify(enclave).getPublicKeys();
  }

  @Test
  public void receiveWithRecipientsPresent() {
    final PublicKey sender = PublicKey.from("sender".getBytes());
    final PublicKey recipient1 = PublicKey.from("recipient1".getBytes());
    final PublicKey recipient2 = PublicKey.from("recipient2".getBytes());

    byte[] randomData = Base64.getEncoder().encode("odd-data".getBytes());
    MessageHash messageHash = new MessageHash(randomData);

    ReceiveRequest receiveRequest =
        ReceiveRequest.Builder.create()
            .withRecipient(mock(PublicKey.class))
            .withTransactionHash(messageHash)
            .build();

    EncryptedTransaction encryptedTransaction = new EncryptedTransaction();
    encryptedTransaction.setHash(messageHash);
    EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getExecHash()).thenReturn("execHash".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(payload.getRecipientKeys()).thenReturn(List.of(recipient1, recipient2));
    when(payload.getSenderKey()).thenReturn(sender);

    encryptedTransaction.setPayload(payload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(encryptedTransaction));

    byte[] expectedOutcome = "Encrypted payload".getBytes();

    when(enclave.unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class)))
        .thenReturn(expectedOutcome);
    when(enclave.getPublicKeys()).thenReturn(Set.of(recipient1, recipient2));

    ReceiveResponse receiveResponse = transactionManager.receive(receiveRequest);

    assertThat(receiveResponse).isNotNull();
    assertThat(receiveResponse.getUnencryptedTransactionData()).isEqualTo(expectedOutcome);
    assertThat(receiveResponse.getManagedParties())
        .containsExactlyInAnyOrder(recipient1, recipient2);
    assertThat(receiveResponse.sender()).isEqualTo(sender);

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave, times(2)).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    verify(enclave, times(2)).getPublicKeys();
  }

  @Test
  public void receiveWithNoRecipientsPresent() {
    final PublicKey sender = PublicKey.from("sender".getBytes());
    final PublicKey recipient1 = PublicKey.from("recipient1".getBytes());

    byte[] randomData = Base64.getEncoder().encode("odd-data".getBytes());
    MessageHash messageHash = new MessageHash(randomData);

    ReceiveRequest receiveRequest =
        ReceiveRequest.Builder.create()
            .withRecipient(mock(PublicKey.class))
            .withTransactionHash(messageHash)
            .build();

    EncryptedTransaction encryptedTransaction = new EncryptedTransaction();

    encryptedTransaction.setHash(messageHash);
    EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getSenderKey()).thenReturn(sender);
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(payload.getRecipientBoxes()).thenReturn(List.of(RecipientBox.from("box1".getBytes())));

    encryptedTransaction.setPayload(payload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(encryptedTransaction));

    byte[] expectedOutcome = "Encrypted payload".getBytes();

    when(enclave.unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class)))
        .thenReturn(expectedOutcome);
    when(enclave.getPublicKeys()).thenReturn(Set.of(recipient1));

    ReceiveResponse receiveResponse = transactionManager.receive(receiveRequest);

    assertThat(receiveResponse).isNotNull();
    assertThat(receiveResponse.getUnencryptedTransactionData()).isEqualTo(expectedOutcome);
    assertThat(receiveResponse.getManagedParties()).containsExactly(recipient1);
    assertThat(receiveResponse.sender()).isEqualTo(sender);

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave, times(3)).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    verify(enclave, times(2)).getPublicKeys();
  }

  @Test
  public void receiveRawTransaction() {
    byte[] keyData = Base64.getEncoder().encode("KEY".getBytes());
    PublicKey recipient = PublicKey.from("recipient".getBytes());
    MessageHash messageHash = new MessageHash(Base64.getDecoder().decode(keyData));

    ReceiveRequest receiveRequest =
        ReceiveRequest.Builder.create()
            .withRecipient(recipient)
            .withRaw(true)
            .withTransactionHash(messageHash)
            .build();

    final EncryptedRawTransaction encryptedTransaction = new EncryptedRawTransaction();

    encryptedTransaction.setSender("sender".getBytes());
    encryptedTransaction.setEncryptedPayload("payload".getBytes());
    encryptedTransaction.setHash(messageHash);
    encryptedTransaction.setEncryptedKey("key".getBytes());

    when(encryptedRawTransactionDAO.retrieveByHash(messageHash))
        .thenReturn(Optional.of(encryptedTransaction));

    when(enclave.unencryptRawPayload(any(RawTransaction.class))).thenReturn("response".getBytes());

    ReceiveResponse response = transactionManager.receive(receiveRequest);

    assertThat(response.getUnencryptedTransactionData()).isEqualTo("response".getBytes());

    verify(enclave).unencryptRawPayload(any(RawTransaction.class));
  }

  @Test
  public void receiveRawTransactionNotFound() {
    byte[] keyData = Base64.getEncoder().encode("KEY".getBytes());
    PublicKey recipient = PublicKey.from("recipient".getBytes());
    MessageHash messageHash = new MessageHash(Base64.getDecoder().decode(keyData));
    ReceiveRequest receiveRequest =
        ReceiveRequest.Builder.create()
            .withTransactionHash(messageHash)
            .withRecipient(recipient)
            .withRaw(true)
            .build();

    when(encryptedRawTransactionDAO.retrieveByHash(messageHash)).thenReturn(Optional.empty());

    assertThatExceptionOfType(TransactionNotFoundException.class)
        .isThrownBy(() -> transactionManager.receive(receiveRequest));
  }

  @Test
  public void receiveWithAffectedContractTransactions() {
    PublicKey sender = PublicKey.from("sender".getBytes());
    byte[] keyData = Base64.getEncoder().encode("KEY".getBytes());
    PublicKey recipient = PublicKey.from("recipient".getBytes());
    MessageHash messageHash = new MessageHash(keyData);

    ReceiveRequest receiveRequest =
        ReceiveRequest.Builder.create()
            .withRecipient(recipient)
            .withTransactionHash(messageHash)
            .build();

    final EncryptedTransaction encryptedTransaction =
        new EncryptedTransaction(messageHash, keyData);
    encryptedTransaction.setHash(messageHash);

    final String b64AffectedTxHash =
        "bfMIqWJ/QGQhkK4USxMBxduzfgo/SIGoCros5bWYfPKUBinlAUCqLVOUAP9q+BgLlsWni1M6rnzfmaqSw2J5hQ==";
    final Map<TxHash, SecurityHash> affectedTxs =
        Map.of(new TxHash(b64AffectedTxHash), SecurityHash.from("encoded".getBytes()));

    EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getExecHash()).thenReturn("execHash".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(payload.getAffectedContractTransactions()).thenReturn(affectedTxs);
    when(payload.getSenderKey()).thenReturn(sender);

    encryptedTransaction.setPayload(payload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(encryptedTransaction));

    byte[] expectedOutcome = "Encrypted payload".getBytes();

    when(enclave.unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class)))
        .thenReturn(expectedOutcome);

    PublicKey publicKey = mock(PublicKey.class);
    when(enclave.getPublicKeys()).thenReturn(Collections.singleton(publicKey));

    ReceiveResponse receiveResponse = transactionManager.receive(receiveRequest);

    assertThat(receiveResponse).isNotNull();

    assertThat(receiveResponse.getUnencryptedTransactionData()).isEqualTo(expectedOutcome);
    assertThat(receiveResponse.getExecHash()).isEqualTo("execHash".getBytes());
    assertThat(receiveResponse.getAffectedTransactions()).hasSize(1);
    assertThat(receiveResponse.sender()).isEqualTo(sender);

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave, times(2)).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    verify(enclave).getPublicKeys();
  }

  @Test
  public void receiveNoTransactionInDatabase() {

    PublicKey recipient = PublicKey.from("recipient".getBytes());

    MessageHash messageHash = mock(MessageHash.class);
    when(messageHash.getHashBytes()).thenReturn("KEY".getBytes());

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getTransactionHash()).thenReturn(messageHash);
    when(receiveRequest.getRecipient()).thenReturn(Optional.of(recipient));

    EncodedPayload payload = mock(EncodedPayload.class);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.empty());

    try {
      transactionManager.receive(receiveRequest);
      failBecauseExceptionWasNotThrown(TransactionNotFoundException.class);
    } catch (TransactionNotFoundException ex) {
      verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    }
  }

  @Test
  public void receiveNoRecipientKeyFound() {

    final byte[] keyData = Base64.getEncoder().encode("KEY".getBytes());
    PublicKey recipient = PublicKey.from("recipient".getBytes());

    MessageHash messageHash = mock(MessageHash.class);
    when(messageHash.getHashBytes()).thenReturn("KEY".getBytes());

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getTransactionHash()).thenReturn(messageHash);
    when(receiveRequest.getRecipient()).thenReturn(Optional.of(recipient));

    EncryptedTransaction encryptedTransaction = new EncryptedTransaction();
    encryptedTransaction.setHash(messageHash);

    EncodedPayload payload = mock(EncodedPayload.class);
    encryptedTransaction.setPayload(payload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(encryptedTransaction));

    PublicKey publicKey = mock(PublicKey.class);
    when(enclave.getPublicKeys()).thenReturn(Collections.singleton(publicKey));

    when(enclave.unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class)))
        .thenThrow(EncryptorException.class);

    try {
      transactionManager.receive(receiveRequest);
      failBecauseExceptionWasNotThrown(RecipientKeyNotFoundException.class);
    } catch (RecipientKeyNotFoundException ex) {
      verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
      verify(enclave).getPublicKeys();
      verify(enclave).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    }
  }

  @Test
  public void receiveHH() {

    byte[] keyData = Base64.getEncoder().encode("KEY".getBytes());
    PublicKey recipient = PublicKey.from("recipient".getBytes());

    MessageHash messageHash = mock(MessageHash.class);
    when(messageHash.getHashBytes()).thenReturn("KEY".getBytes());

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getRecipient()).thenReturn(Optional.of(recipient));
    when(receiveRequest.getTransactionHash()).thenReturn(messageHash);

    EncryptedTransaction encryptedTransaction = new EncryptedTransaction(messageHash, null);

    EncodedPayload payload = mock(EncodedPayload.class);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(encryptedTransaction));

    byte[] expectedOutcome = "Encrypted payload".getBytes();

    when(enclave.unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class)))
        .thenReturn(expectedOutcome);

    PublicKey publicKey = mock(PublicKey.class);
    when(enclave.getPublicKeys()).thenReturn(Collections.singleton(publicKey));

    final Throwable throwable = catchThrowable(() -> transactionManager.receive(receiveRequest));

    assertThat(throwable).isInstanceOf(IllegalStateException.class);

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
  }

  @Test
  public void receiveNullRecipientThrowsNoRecipientKeyFound() {

    byte[] keyData = Base64.getEncoder().encode("KEY".getBytes());

    MessageHash transactionHash = mock(MessageHash.class);
    when(transactionHash.getHashBytes()).thenReturn("KEY".getBytes());
    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getRecipient()).thenReturn(Optional.empty());
    when(receiveRequest.getTransactionHash()).thenReturn(transactionHash);

    EncryptedTransaction encryptedTransaction = new EncryptedTransaction();
    encryptedTransaction.setHash(transactionHash);

    EncodedPayload payload = mock(EncodedPayload.class);
    encryptedTransaction.setPayload(payload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(encryptedTransaction));

    PublicKey publicKey = mock(PublicKey.class);
    when(enclave.getPublicKeys()).thenReturn(Collections.singleton(publicKey));

    when(enclave.unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class)))
        .thenThrow(EncryptorException.class);

    try {
      transactionManager.receive(receiveRequest);
      failBecauseExceptionWasNotThrown(RecipientKeyNotFoundException.class);
    } catch (RecipientKeyNotFoundException ex) {
      verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
      verify(enclave).getPublicKeys();
      verify(enclave).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    }
  }

  @Test
  public void receiveEmptyRecipientThrowsNoRecipientKeyFound() {

    byte[] keyData = Base64.getEncoder().encode("KEY".getBytes());
    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    MessageHash transactionHash = mock(MessageHash.class);
    when(transactionHash.getHashBytes()).thenReturn("KEY".getBytes());
    when(receiveRequest.getTransactionHash()).thenReturn(transactionHash);

    EncryptedTransaction encryptedTransaction = new EncryptedTransaction();
    encryptedTransaction.setHash(transactionHash);
    encryptedTransaction.setEncodedPayload(keyData);

    EncodedPayload payload = mock(EncodedPayload.class);
    encryptedTransaction.setPayload(payload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(encryptedTransaction));

    PublicKey publicKey = mock(PublicKey.class);
    when(enclave.getPublicKeys()).thenReturn(Collections.singleton(publicKey));

    when(enclave.unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class)))
        .thenThrow(EncryptorException.class);

    try {
      transactionManager.receive(receiveRequest);
      failBecauseExceptionWasNotThrown(RecipientKeyNotFoundException.class);
    } catch (RecipientKeyNotFoundException ex) {
      verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
      verify(enclave).getPublicKeys();
      verify(enclave).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    }
  }

  @Test
  public void storeRaw() {
    byte[] sender = "SENDER".getBytes();
    RawTransaction rawTransaction =
        new RawTransaction(
            "CIPHERTEXT".getBytes(),
            "SomeKey".getBytes(),
            new Nonce("nonce".getBytes()),
            PublicKey.from(sender));

    when(enclave.encryptRawPayload(any(), any())).thenReturn(rawTransaction);

    byte[] payload = Base64.getEncoder().encode("PAYLOAD".getBytes());
    StoreRawRequest sendRequest = mock(StoreRawRequest.class);
    when(sendRequest.getSender()).thenReturn(PublicKey.from(sender));
    when(sendRequest.getPayload()).thenReturn(payload);

    MessageHash expectedHash = new MessageHash(mockDigest.digest("CIPHERTEXT".getBytes()));

    StoreRawResponse result = transactionManager.store(sendRequest);

    assertThat(result).isNotNull();
    assertThat(result.getHash().getHashBytes()).containsExactly(expectedHash.getHashBytes());

    verify(enclave).encryptRawPayload(eq(payload), eq(PublicKey.from(sender)));
    verify(encryptedRawTransactionDAO)
        .save(
            argThat(
                et -> {
                  assertThat(et.getEncryptedKey()).containsExactly("SomeKey".getBytes());
                  assertThat(et.getEncryptedPayload()).containsExactly("CIPHERTEXT".getBytes());
                  assertThat(et.getHash()).isEqualTo(expectedHash);
                  assertThat(et.getNonce()).containsExactly("nonce".getBytes());
                  assertThat(et.getSender()).containsExactly(sender);
                  return true;
                }));
  }

  @Test(expected = NullPointerException.class)
  public void storeRawWithEmptySender() {
    byte[] sender = "SENDER".getBytes();
    RawTransaction rawTransaction =
        new RawTransaction(
            "CIPHERTEXT".getBytes(),
            "SomeKey".getBytes(),
            new Nonce("nonce".getBytes()),
            PublicKey.from(sender));
    when(enclave.encryptRawPayload(any(), any())).thenReturn(rawTransaction);
    when(enclave.defaultPublicKey()).thenReturn(PublicKey.from(sender));
    byte[] payload = Base64.getEncoder().encode("PAYLOAD".getBytes());
    StoreRawRequest sendRequest = StoreRawRequest.Builder.create().withPayload(payload).build();

    MessageHash expectedHash = new MessageHash(mockDigest.digest("CIPHERTEXT".getBytes()));

    try {
      transactionManager.store(sendRequest);
      failBecauseExceptionWasNotThrown(NullPointerException.class);
    } catch (NullPointerException ex) {
      assertThat(ex).hasMessage("Sender is required");
      verify(enclave).encryptRawPayload(eq(payload), eq(PublicKey.from(sender)));
      verify(enclave).defaultPublicKey();

      verify(encryptedRawTransactionDAO)
          .save(
              argThat(
                  et -> {
                    assertThat(et.getEncryptedKey()).containsExactly("SomeKey".getBytes());
                    assertThat(et.getEncryptedPayload()).containsExactly("CIPHERTEXT".getBytes());
                    assertThat(et.getHash()).isEqualTo(expectedHash);
                    assertThat(et.getNonce()).containsExactly("nonce".getBytes());
                    assertThat(et.getSender()).containsExactly(sender);
                    return true;
                  }));
    }
  }

  @Test
  public void constructWithLessArgs() {

    TransactionManager tm =
        new TransactionManagerImpl(
            encryptedTransactionDAO,
            batchPayloadPublisher,
            enclave,
            encryptedRawTransactionDAO,
            resendManager,
            privacyHelper,
            mockDigest);

    assertThat(tm).isNotNull();
  }

  @Test
  public void isSenderThrowsOnMissingTransaction() {

    MessageHash transactionHash = mock(MessageHash.class);
    when(transactionHash.getHashBytes()).thenReturn("DUMMY_TRANSACTION".getBytes());

    when(encryptedTransactionDAO.retrieveByHash(transactionHash)).thenReturn(Optional.empty());

    final Throwable throwable = catchThrowable(() -> transactionManager.isSender(transactionHash));

    assertThat(throwable)
        .isInstanceOf(TransactionNotFoundException.class)
        .hasMessage("Message with hash RFVNTVlfVFJBTlNBQ1RJT04= was not found");

    verify(encryptedTransactionDAO).retrieveByHash(transactionHash);
  }

  @Test
  public void isSenderReturnsFalseIfSenderNotFoundInPublicKeys() {
    final MessageHash transactionHash = mock(MessageHash.class);
    when(transactionHash.getHashBytes()).thenReturn("DUMMY_TRANSACTION".getBytes());

    final EncodedPayload encodedPayload = mock(EncodedPayload.class);
    PublicKey sender = mock(PublicKey.class);
    when(encodedPayload.getSenderKey()).thenReturn(sender);

    final EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    when(encryptedTransaction.getPayload()).thenReturn(encodedPayload);

    when(encryptedTransactionDAO.retrieveByHash(transactionHash))
        .thenReturn(Optional.of(encryptedTransaction));

    when(enclave.getPublicKeys()).thenReturn(Set.of());

    final boolean isSender = transactionManager.isSender(transactionHash);

    assertThat(isSender).isFalse();

    verify(enclave).getPublicKeys();
    verify(encryptedTransactionDAO).retrieveByHash(transactionHash);
  }

  @Test
  public void isSenderReturnsTrueIfSender() {

    MessageHash transactionHash = mock(MessageHash.class);
    when(transactionHash.getHashBytes()).thenReturn("DUMMY_TRANSACTION".getBytes());

    final PublicKey senderKey = mock(PublicKey.class);
    final EncodedPayload encodedPayload = mock(EncodedPayload.class);
    when(encodedPayload.getSenderKey()).thenReturn(senderKey);

    final EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    when(encryptedTransaction.getPayload()).thenReturn(encodedPayload);

    when(encryptedTransactionDAO.retrieveByHash(transactionHash))
        .thenReturn(Optional.of(encryptedTransaction));

    when(enclave.getPublicKeys()).thenReturn(Set.of(senderKey));

    final boolean isSender = transactionManager.isSender(transactionHash);

    assertThat(isSender).isTrue();

    verify(enclave).getPublicKeys();
    verify(encryptedTransactionDAO).retrieveByHash(transactionHash);
  }

  @Test
  public void getParticipantsThrowsOnMissingTransaction() {

    MessageHash transactionHash = mock(MessageHash.class);
    when(transactionHash.getHashBytes()).thenReturn("DUMMY_TRANSACTION".getBytes());

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.empty());

    final Throwable throwable =
        catchThrowable(() -> transactionManager.getParticipants(transactionHash));

    assertThat(throwable)
        .isInstanceOf(TransactionNotFoundException.class)
        .hasMessage("Message with hash RFVNTVlfVFJBTlNBQ1RJT04= was not found");

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
  }

  @Test
  public void getParticipantsReturnsAllRecipients() {

    MessageHash transactionHash = mock(MessageHash.class);
    when(transactionHash.getHashBytes()).thenReturn("DUMMY_TRANSACTION".getBytes());

    final PublicKey senderKey = mock(PublicKey.class);
    final PublicKey recipientKey = mock(PublicKey.class);

    final EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    final EncodedPayload encodedPayload = mock(EncodedPayload.class);
    when(encodedPayload.getRecipientKeys()).thenReturn(List.of(senderKey, recipientKey));
    when(encryptedTransaction.getPayload()).thenReturn(encodedPayload);

    when(encryptedTransactionDAO.retrieveByHash(transactionHash))
        .thenReturn(Optional.of(encryptedTransaction));

    final List<PublicKey> participants = transactionManager.getParticipants(transactionHash);

    assertThat(participants).containsExactlyInAnyOrder(senderKey, recipientKey);

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
  }

  @Test
  public void getMandatoryRecipients() {

    MessageHash transactionHash = mock(MessageHash.class);
    when(transactionHash.getHashBytes()).thenReturn("DUMMY_TRANSACTION".getBytes());

    final PublicKey senderKey = mock(PublicKey.class);
    final PublicKey recipientKey = mock(PublicKey.class);

    final EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    final EncodedPayload encodedPayload = mock(EncodedPayload.class);
    when(encodedPayload.getRecipientKeys()).thenReturn(List.of(senderKey, recipientKey));
    when(encodedPayload.getPrivacyMode()).thenReturn(PrivacyMode.MANDATORY_RECIPIENTS);
    when(encodedPayload.getMandatoryRecipients()).thenReturn(Set.of(recipientKey));
    when(encryptedTransaction.getPayload()).thenReturn(encodedPayload);

    when(encryptedTransactionDAO.retrieveByHash(transactionHash))
        .thenReturn(Optional.of(encryptedTransaction));

    final Set<PublicKey> participants = transactionManager.getMandatoryRecipients(transactionHash);

    assertThat(participants).containsExactly(recipientKey);

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
  }

  @Test
  public void getMandatoryRecipientsNotAvailable() {

    MessageHash transactionHash = mock(MessageHash.class);
    when(transactionHash.getHashBytes()).thenReturn("DUMMY_TRANSACTION".getBytes());

    final PublicKey senderKey = mock(PublicKey.class);
    final PublicKey recipientKey = mock(PublicKey.class);

    final EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    final EncodedPayload encodedPayload = mock(EncodedPayload.class);
    when(encodedPayload.getRecipientKeys()).thenReturn(List.of(senderKey, recipientKey));
    when(encodedPayload.getPrivacyMode()).thenReturn(PrivacyMode.PARTY_PROTECTION);
    when(encryptedTransaction.getPayload()).thenReturn(encodedPayload);

    when(encryptedTransactionDAO.retrieveByHash(transactionHash))
        .thenReturn(Optional.of(encryptedTransaction));

    assertThatExceptionOfType(MandatoryRecipientsNotAvailableException.class)
        .isThrownBy(() -> transactionManager.getMandatoryRecipients(transactionHash))
        .withMessageContaining(
            "Operation invalid. Transaction found is not a mandatory recipients privacy type");

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
  }

  @Test
  public void defaultPublicKey() {
    transactionManager.defaultPublicKey();
    verify(enclave).defaultPublicKey();
  }

  @Test
  public void upcheckReturnsTrue() {

    when(encryptedTransactionDAO.upcheck()).thenReturn(true);
    when(encryptedRawTransactionDAO.upcheck()).thenReturn(true);

    assertThat(transactionManager.upcheck()).isTrue();

    verify(encryptedRawTransactionDAO).upcheck();
    verify(encryptedTransactionDAO).upcheck();
  }

  @Test
  public void upcheckReturnsFalseIfEncryptedTransactionDBFail() {

    when(encryptedTransactionDAO.upcheck()).thenReturn(false);
    when(encryptedRawTransactionDAO.upcheck()).thenReturn(true);

    assertThat(transactionManager.upcheck()).isFalse();

    verify(encryptedRawTransactionDAO).upcheck();
    verify(encryptedTransactionDAO).upcheck();
  }

  @Test
  public void upcheckReturnsFalseIfEncryptedRawTransactionDBFail() {

    when(encryptedTransactionDAO.upcheck()).thenReturn(true);
    when(encryptedRawTransactionDAO.upcheck()).thenReturn(false);

    assertThat(transactionManager.upcheck()).isFalse();

    verify(encryptedRawTransactionDAO).upcheck();
  }

  @Test
  public void create() {
    TransactionManager expected = mock(TransactionManager.class);
    TransactionManager result;
    try (var mockedStaticServiceLoader = mockStatic(ServiceLoader.class)) {

      ServiceLoader<TransactionManager> serviceLoader = mock(ServiceLoader.class);
      when(serviceLoader.findFirst()).thenReturn(Optional.of(expected));

      mockedStaticServiceLoader
          .when(() -> ServiceLoader.load(TransactionManager.class))
          .thenReturn(serviceLoader);

      result = TransactionManager.create();

      verify(serviceLoader).findFirst();
      verifyNoMoreInteractions(serviceLoader);

      mockedStaticServiceLoader.verify(() -> ServiceLoader.load(TransactionManager.class));
      mockedStaticServiceLoader.verifyNoMoreInteractions();
    }

    assertThat(result).isSameAs(expected);
  }
}
