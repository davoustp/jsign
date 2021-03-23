/**
 * Copyright 2019 Emmanuel Bourg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.jsign.mscab;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Object;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.DigestInfo;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessable;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;

import net.jsign.DigestAlgorithm;
import net.jsign.Signable;
import net.jsign.asn1.authenticode.AuthenticodeObjectIdentifiers;
import net.jsign.asn1.authenticode.SpcAttributeTypeAndOptionalValue;
import net.jsign.asn1.authenticode.SpcIndirectDataContent;
import net.jsign.asn1.authenticode.SpcPeImageData;

import static net.jsign.ChannelUtils.*;

/**
 * Microsoft Cabinet File.
 *
 * This class is thread safe.
 *
 * @see <a href="http://download.microsoft.com/download/5/0/1/501ED102-E53F-4CE0-AA6B-B0F93629DDC6/Exchange/[MS-CAB].pdf">[MS-CAB]: Cabinet File Format</a>
 *
 * @author Joseph Lee
 * @since 3.2
 */
public class MSCabinetFile implements Signable, Closeable {

    private final CFHeader header = new CFHeader();

    private final SeekableByteChannel channel;

    /**
     * Tells if the specified file is a MS Cabinet file.
     *
     * @param file the file to check
     * @return <code>true</code> if the file is a MS Cabinet, <code>false</code> otherwise
     * @throws IOException if an I/O error occurs
     */
    public static boolean isMSCabinetFile(File file) throws IOException {
        if (!file.exists() && !file.isFile()) {
            return false;
        }

        try {
            MSCabinetFile cabFile = new MSCabinetFile(file);
            cabFile.close();
            return true;
        } catch (IOException e) {
            if (e.getMessage().contains("MSCabinet header signature not found") || e.getMessage().contains("MSCabinet file too short")) {
                return false;
            } else {
                throw e;
            }
        }
    }

    /**
     * Create a MSCabinetFile from the specified file.
     *
     * @param file the file to open
     * @throws IOException if an I/O error occurs
     */
    public MSCabinetFile(File file) throws IOException {
        this(Files.newByteChannel(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE));
    }

    /**
     * Create a MSCabinetFile from the specified channel.
     *
     * @param channel the channel to read the file from
     * @throws IOException if an I/O error occurs
     */
    public MSCabinetFile(SeekableByteChannel channel) throws IOException {
        this.channel = channel;

        channel.position(0);
        header.read(channel);

        if (header.csumHeader != 0) {
            throw new IOException("MSCabinet file is corrupt: invalid reserved field in the header");
        }

        if (header.isReservePresent()) {
            if (header.cbCFHeader != CABSignature.SIZE) {
                throw new IOException("MSCabinet file is corrupt: cabinet reserved area size is " + header.cbCFHeader + " instead of " + CABSignature.SIZE);
            }

            CABSignature cabsig = header.getSignature();
            if (cabsig.header != CABSignature.HEADER) {
                throw new IOException("MSCabinet file is corrupt: signature header is " + cabsig.header);
            }

            if (cabsig.offset < channel.size() && (cabsig.offset + cabsig.length) > channel.size()) {
                throw new IOException("MSCabinet file is corrupt: signature data (offset=" + cabsig.offset + ", size=" + cabsig.length + ") after the end of the file");
            }
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    @Override
    public synchronized byte[] computeDigest(MessageDigest digest) throws IOException {
        CFHeader modifiedHeader = new CFHeader(header);
        if (!header.isReservePresent()) {
            modifiedHeader.cbCFHeader = CABSignature.SIZE;
            modifiedHeader.cbCabinet += 4 + CABSignature.SIZE;
            modifiedHeader.coffFiles += 4 + CABSignature.SIZE;
            modifiedHeader.flags |= CFHeader.FLAG_RESERVE_PRESENT;

            CABSignature cabsig = new CABSignature();
            cabsig.offset = (int) modifiedHeader.cbCabinet;

            modifiedHeader.abReserved = cabsig.array();
        }
        modifiedHeader.headerDigestUpdate(digest);

        channel.position(header.getHeaderSize());

        if (header.hasPreviousCabinet()) {
            digest.update(readNullTerminatedString(channel)); // szCabinetPrev
            digest.update(readNullTerminatedString(channel)); // szDiskPrev
        }

        if (header.hasNextCabinet()) {
            digest.update(readNullTerminatedString(channel)); // szCabinetNext
            digest.update(readNullTerminatedString(channel)); // szDiskNext
        }

        for (int i = 0; i < header.cFolders; i++) {
            CFFolder folder = CFFolder.read(channel);
            if (!header.isReservePresent()) {
                folder.coffCabStart += 4 + CABSignature.SIZE;
            }
            folder.digest(digest);
        }

        long endPosition = header.hasSignature() ? header.getSignature().offset : channel.size();
        updateDigest(channel, digest, channel.position(), endPosition);

        return digest.digest();
    }

    @Override
    public ASN1Object createIndirectData(DigestAlgorithm digestAlgorithm) throws IOException {
        AlgorithmIdentifier algorithmIdentifier = new AlgorithmIdentifier(digestAlgorithm.oid, DERNull.INSTANCE);
        DigestInfo digestInfo = new DigestInfo(algorithmIdentifier, computeDigest(digestAlgorithm.getMessageDigest()));
        SpcAttributeTypeAndOptionalValue data = new SpcAttributeTypeAndOptionalValue(AuthenticodeObjectIdentifiers.SPC_CAB_DATA_OBJID, new SpcPeImageData());

        return new SpcIndirectDataContent(data, digestInfo);
    }

    @Override
    public synchronized List<CMSSignedData> getSignatures() throws IOException {
        List<CMSSignedData> signatures = new ArrayList<>();
        try {
            CABSignature cabsig = header.getSignature();
            if (cabsig != null && cabsig.offset > 0) {
                byte[] buffer = new byte[cabsig.length];
                channel.position(cabsig.offset);
                channel.read(ByteBuffer.wrap(buffer));

                CMSSignedData signedData;
                signedData = new CMSSignedData((CMSProcessable) null, ContentInfo.getInstance(new ASN1InputStream(buffer).readObject()));
                signatures.add(signedData);

                SignerInformation signerInformation = signedData.getSignerInfos().getSigners().iterator().next();
                AttributeTable unsignedAttributes = signerInformation.getUnsignedAttributes();
                if (unsignedAttributes != null) {
                    Attribute nestedSignatures = unsignedAttributes.get(AuthenticodeObjectIdentifiers.SPC_NESTED_SIGNATURE_OBJID);
                    if (nestedSignatures != null) {
                        for (ASN1Encodable nestedSignature : nestedSignatures.getAttrValues()) {
                            signatures.add(new CMSSignedData((CMSProcessable) null, ContentInfo.getInstance(nestedSignature)));
                        }
                    }
                }
            }
        } catch (CMSException e) {
            throw new IOException(e);
        }
        return signatures;
    }

    @Override
    public synchronized void setSignature(CMSSignedData signature) throws IOException {
        byte[] content = signature.toASN1Structure().getEncoded("DER");

        boolean modified = false;

        File backupFile = null;
        SeekableByteChannel backupChannel = null;
        SeekableByteChannel readChannel = channel;
        readChannel.position(header.getHeaderSize());

        int siglen = header.hasSignature() ? header.getSignature().length : 0;

        try {
            if (!header.isReservePresent()) {
                backupFile = File.createTempFile("tmp", ".cab");
                backupChannel = Files.newByteChannel(backupFile.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE);
                copy(channel, backupChannel);
                readChannel = backupChannel;
                readChannel.position(header.getHeaderSize());

                modified = true;

                header.cbCFHeader = CABSignature.SIZE;
                header.cbCabinet += 4 + CABSignature.SIZE;
                header.coffFiles += 4 + CABSignature.SIZE;
                header.flags |= CFHeader.FLAG_RESERVE_PRESENT;
                header.abReserved = new byte[CABSignature.SIZE];
            }

            CABSignature cabsig = new CABSignature(header.abReserved);
            cabsig.header = CABSignature.HEADER;
            cabsig.offset = (int) header.cbCabinet;
            cabsig.length = content.length;
            header.abReserved = cabsig.array();

            channel.position(0);
            {
                ByteBuffer buffer = ByteBuffer.allocate(header.getHeaderSize()).order(ByteOrder.LITTLE_ENDIAN);
                header.write(buffer);
                buffer.flip();
                channel.write(buffer);
            }

            if (header.hasPreviousCabinet()) {
                channel.write(ByteBuffer.wrap(readNullTerminatedString(readChannel))); // szCabinetPrev
                channel.write(ByteBuffer.wrap(readNullTerminatedString(readChannel))); // szDiskPrev
            }

            if (header.hasNextCabinet()) {
                channel.write(ByteBuffer.wrap(readNullTerminatedString(readChannel))); // szCabinetNext
                channel.write(ByteBuffer.wrap(readNullTerminatedString(readChannel))); // szDiskNext
            }

            if (modified) {
                for (int i = 0; i < header.cFolders; i++) {
                    CFFolder folder = CFFolder.read(readChannel);
                    folder.coffCabStart += 4 + CABSignature.SIZE;

                    folder.write(channel);
                }
            }

            long payloadSize = (readChannel.size() - readChannel.position()) - siglen;
            copy(readChannel, channel, payloadSize);

            channel.write(ByteBuffer.wrap(content));

            if (channel.position() < channel.size()) {
                channel.truncate(channel.position());
            }
        } finally {
            if (backupChannel != null) {
                backupChannel.close();
            }
            if (backupFile != null) {
                backupFile.delete();
            }
        }
    }

    @Override
    public void save() {
    }
}
