/*******************************************************************************
 * Copyright (c) 2016 Sebastian Stenzel and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the accompanying LICENSE.txt.
 *
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 *******************************************************************************/
package org.cryptomator.cryptolib.v1;

import static org.cryptomator.cryptolib.v1.Constants.DEFAULT_SCRYPT_BLOCK_SIZE;
import static org.cryptomator.cryptolib.v1.Constants.DEFAULT_SCRYPT_COST_PARAM;
import static org.cryptomator.cryptolib.v1.Constants.DEFAULT_SCRYPT_SALT_LENGTH;
import static org.cryptomator.cryptolib.v1.Constants.KEY_LEN_BYTES;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.DestroyFailedException;
import javax.security.auth.Destroyable;

import org.cryptomator.cryptolib.api.Cryptor;
import org.cryptomator.cryptolib.api.KeyFile;
import org.cryptomator.cryptolib.common.AesKeyWrap;
import org.cryptomator.cryptolib.common.MacSupplier;
import org.cryptomator.cryptolib.common.Scrypt;

public class CryptorImpl implements Cryptor {

	private final SecretKey encKey;
	private final SecretKey macKey;
	private final SecureRandom random;
	private final FileContentCryptorImpl fileContentCryptor;
	private final FileHeaderCryptorImpl fileHeaderCryptor;
	private final FileNameCryptorImpl fileNameCryptor;

	/**
	 * Package-private constructor.
	 * Use {@link CryptorProviderImpl#createNew()} or {@link CryptorProviderImpl#createFromKeyFile(byte[], CharSequence)} to obtain a Cryptor instance.
	 */
	CryptorImpl(SecretKey encKey, SecretKey macKey, SecureRandom random) {
		this.encKey = encKey;
		this.macKey = macKey;
		this.random = random;
		this.fileHeaderCryptor = new FileHeaderCryptorImpl(encKey, macKey, random);
		this.fileContentCryptor = new FileContentCryptorImpl(macKey, random);
		this.fileNameCryptor = new FileNameCryptorImpl(encKey, macKey);
	}

	@Override
	public FileContentCryptorImpl fileContentCryptor() {
		return fileContentCryptor;
	}

	@Override
	public FileHeaderCryptorImpl fileHeaderCryptor() {
		return fileHeaderCryptor;
	}

	@Override
	public FileNameCryptorImpl fileNameCryptor() {
		return fileNameCryptor;
	}

	@Override
	public boolean isDestroyed() {
		// SecretKey did not implement Destroyable in Java 7:
		if (encKey instanceof Destroyable && macKey instanceof Destroyable) {
			return ((Destroyable) encKey).isDestroyed() && ((Destroyable) macKey).isDestroyed();
		} else {
			return false;
		}
	}

	@Override
	public void destroy() {
		destroyQuietly(encKey);
		destroyQuietly(macKey);
	}

	@Override
	public KeyFile writeKeysToMasterkeyFile(CharSequence passphrase, int vaultVersion) {
		final byte[] scryptSalt = new byte[DEFAULT_SCRYPT_SALT_LENGTH];
		random.nextBytes(scryptSalt);

		final byte[] kekBytes = Scrypt.scrypt(passphrase, scryptSalt, DEFAULT_SCRYPT_COST_PARAM, DEFAULT_SCRYPT_BLOCK_SIZE, KEY_LEN_BYTES);
		final byte[] wrappedEncryptionKey;
		final byte[] wrappedMacKey;
		try {
			final SecretKey kek = new SecretKeySpec(kekBytes, Constants.ENC_ALG);
			wrappedEncryptionKey = AesKeyWrap.wrap(kek, encKey);
			wrappedMacKey = AesKeyWrap.wrap(kek, macKey);
		} finally {
			Arrays.fill(kekBytes, (byte) 0x00);
		}

		final Mac mac = MacSupplier.HMAC_SHA256.withKey(macKey);
		final byte[] versionMac = mac.doFinal(ByteBuffer.allocate(Integer.SIZE / Byte.SIZE).putInt(vaultVersion).array());

		final KeyFileImpl keyfile = new KeyFileImpl();
		keyfile.setVersion(vaultVersion);
		keyfile.scryptSalt = scryptSalt;
		keyfile.scryptCostParam = DEFAULT_SCRYPT_COST_PARAM;
		keyfile.scryptBlockSize = DEFAULT_SCRYPT_BLOCK_SIZE;
		keyfile.encryptionMasterKey = wrappedEncryptionKey;
		keyfile.macMasterKey = wrappedMacKey;
		keyfile.versionMac = versionMac;
		return keyfile;
	}

	private void destroyQuietly(SecretKey key) {
		try {
			if (key instanceof Destroyable) {
				((Destroyable) key).destroy();
			}
		} catch (DestroyFailedException e) {
			// ignore
		}
	}

}