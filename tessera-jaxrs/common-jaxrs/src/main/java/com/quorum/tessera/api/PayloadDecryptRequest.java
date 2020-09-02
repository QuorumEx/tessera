package com.quorum.tessera.api;

import com.quorum.tessera.config.adapters.MapAdapter;
import com.quorum.tessera.enclave.EncodedPayload;
import com.quorum.tessera.enclave.TxHash;
import com.quorum.tessera.encryption.PublicKey;

import javax.xml.bind.annotation.*;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@XmlRootElement
@XmlAccessorType(XmlAccessType.FIELD)
public class PayloadDecryptRequest {

    @XmlElement
    @XmlMimeType("base64Binary")
    private byte[] senderKey;

    @XmlElement
    @XmlMimeType("base64Binary")
    private byte[] cipherText;

    @XmlElement
    @XmlMimeType("base64Binary")
    private byte[] cipherTextNonce;

    @XmlElement
    @XmlMimeType("base64Binary")
    private List<byte[]> recipientBoxes;

    @XmlElement
    @XmlMimeType("base64Binary")
    private byte[] recipientNonce;

    @XmlElement
    @XmlElementWrapper
    @XmlMimeType("base64Binary")
    private List<byte[]> recipientKeys;

    @XmlElement
    private int privacyMode;

    @XmlElement
    @XmlJavaTypeAdapter(MapAdapter.class)
    private Map<String, String> affectedContractTransactions;

    @XmlElement
    @XmlMimeType("base64Binary")
    private byte[] execHash;

    public byte[] getSenderKey() {
        return senderKey;
    }

    public void setSenderKey(final byte[] senderKey) {
        this.senderKey = senderKey;
    }

    public byte[] getCipherText() {
        return cipherText;
    }

    public void setCipherText(final byte[] cipherText) {
        this.cipherText = cipherText;
    }

    public byte[] getCipherTextNonce() {
        return cipherTextNonce;
    }

    public void setCipherTextNonce(final byte[] cipherTextNonce) {
        this.cipherTextNonce = cipherTextNonce;
    }

    public List<byte[]> getRecipientBoxes() {
        return recipientBoxes;
    }

    public void setRecipientBoxes(final List<byte[]> recipientBoxes) {
        this.recipientBoxes = recipientBoxes;
    }

    public byte[] getRecipientNonce() {
        return recipientNonce;
    }

    public void setRecipientNonce(final byte[] recipientNonce) {
        this.recipientNonce = recipientNonce;
    }

    public List<byte[]> getRecipientKeys() {
        return recipientKeys;
    }

    public void setRecipientKeys(final List<byte[]> recipientKeys) {
        this.recipientKeys = recipientKeys;
    }

    public int getPrivacyMode() {
        return privacyMode;
    }

    public void setPrivacyMode(final int privacyMode) {
        this.privacyMode = privacyMode;
    }

    public Map<String, String> getAffectedContractTransactions() {
        return affectedContractTransactions;
    }

    public void setAffectedContractTransactions(final Map<String, String> affectedContractTransactions) {
        this.affectedContractTransactions = affectedContractTransactions;
    }

    public byte[] getExecHash() {
        return execHash;
    }

    public void setExecHash(final byte[] execHash) {
        this.execHash = execHash;
    }

    public EncodedPayload toEncodedPayload() {
        Base64.Decoder decoder = Base64.getDecoder();
        final Map<TxHash, byte[]> affectedTxns = affectedContractTransactions.entrySet()
            .stream()
            .collect(Collectors.toMap(e -> TxHash.from(decoder.decode(e.getKey())), e -> decoder.decode(e.getValue())));

        return EncodedPayload.Builder.create()
            .withSenderKey(PublicKey.from(senderKey))
            .withCipherText(cipherText)
            .withCipherTextNonce(cipherTextNonce)
            .withRecipientBoxes(recipientBoxes)
            .withRecipientNonce(recipientNonce)
            .withRecipientKeys(recipientKeys.stream().map(PublicKey::from).collect(Collectors.toList()))
            .withPrivacyFlag(privacyMode)
            .withAffectedContractTransactions(affectedTxns)
            .withExecHash(execHash)
            .build();
    }

}
