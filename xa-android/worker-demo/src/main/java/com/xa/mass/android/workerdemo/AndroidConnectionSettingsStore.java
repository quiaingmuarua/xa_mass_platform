package com.xa.mass.android.workerdemo;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private settings only; this store does not create or control a Worker. */
final class AndroidConnectionSettingsStore {
    static final String FILE_NAME = "runtime-connection.enc";
    static final int MAX_FILE_BYTES = 65_536;
    private static final byte[] DEFAULT_MARKER = "build-default\n".getBytes(StandardCharsets.US_ASCII);
    private final FileAccess files;
    private final SecretCipher cipher;

    AndroidConnectionSettingsStore(Context context) {
        this(new AtomicSettingsFile(() -> new File(context.getNoBackupFilesDir(), FILE_NAME)), new KeystoreCipher());
    }

    AndroidConnectionSettingsStore(FileAccess files, SecretCipher cipher) {
        this.files = Objects.requireNonNull(files, "files");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    synchronized AndroidConnectionSettings load() {
        byte[] plaintext = null;
        try {
            byte[] stored = files.read();
            if (stored == null || Arrays.equals(DEFAULT_MARKER, stored)) return null;
            if (stored.length == 0 || stored.length > MAX_FILE_BYTES) throw new IOException();
            plaintext = cipher.decrypt(stored);
            if (plaintext.length == 0 || plaintext.length > MAX_FILE_BYTES) throw new IOException();
            return AndroidConnectionSettings.decodeFromStorage(plaintext);
        } catch (Exception unreadable) {
            // Parsing/provider causes may contain credential material. Do not chain them into diagnostics.
            throw new IllegalStateException("Unable to read saved connection settings");
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    synchronized void save(AndroidConnectionSettings settings) {
        if (settings == null || !settings.hasGatewayCredential()) {
            throw new IllegalArgumentException("Complete authenticated settings are required");
        }
        byte[] plaintext = null;
        try {
            plaintext = settings.encodeForStorage();
            if (plaintext.length > MAX_FILE_BYTES - 28) throw new IOException();
            byte[] encrypted = cipher.encrypt(plaintext);
            if (encrypted.length == 0 || encrypted.length > MAX_FILE_BYTES) throw new IOException();
            files.write(encrypted);
        } catch (Exception unavailable) {
            throw new IllegalStateException("Unable to save connection settings");
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    synchronized void clear() {
        try {
            // One atomic replacement restores defaults even if the old key or ciphertext is unreadable.
            files.write(DEFAULT_MARKER.clone());
        } catch (Exception unavailable) {
            throw new IllegalStateException("Unable to restore default connection settings");
        }
    }

    interface FileAccess {
        byte[] read() throws IOException;
        void write(byte[] bytes) throws IOException;
    }

    interface SecretCipher {
        byte[] encrypt(byte[] plaintext) throws GeneralSecurityException;
        byte[] decrypt(byte[] encrypted) throws GeneralSecurityException;
    }

    static final class AtomicSettingsFile implements FileAccess {
        private final Supplier<File> location;
        private AtomicFile atomic;

        AtomicSettingsFile(File path) { this(() -> path); }
        AtomicSettingsFile(Supplier<File> location) { this.location = location; }
        AtomicSettingsFile(AtomicFile atomic) { this.location = atomic::getBaseFile; this.atomic = atomic; }

        private AtomicFile atomic() {
            if (atomic == null) atomic = new AtomicFile(location.get());
            return atomic;
        }

        @Override public byte[] read() throws IOException {
            AtomicFile atomic = atomic();
            File base = atomic.getBaseFile();
            File legacyBackup = new File(base.getPath() + ".bak");
            if (!base.exists() && !legacyBackup.exists()) return null;
            try (FileInputStream input = atomic.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_FILE_BYTES) throw new IOException("Settings exceed the read bound");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        }

        @Override public void write(byte[] bytes) throws IOException {
            AtomicFile atomic = atomic();
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("Settings exceed the write bound");
            FileOutputStream output = null;
            try {
                output = atomic.startWrite();
                output.write(bytes);
                output.getFD().sync();
                atomic.finishWrite(output);
                output = null;
                // AtomicFile can report a failed rename only through its logger; verify publication.
                if (!Arrays.equals(bytes, read())) throw new IOException("Settings publication was not confirmed");
            } catch (IOException | RuntimeException failure) {
                if (output != null) atomic.failWrite(output);
                throw failure;
            }
        }
    }

    static final class KeystoreCipher implements SecretCipher {
        static final String KEY_ALIAS = "xa-mass-runtime-connection";
        private static final int IV_BYTES = 12;

        @Override public byte[] encrypt(byte[] plaintext) throws GeneralSecurityException {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) throw new GeneralSecurityException("Invalid GCM nonce");
            byte[] content = cipher.doFinal(plaintext);
            byte[] encrypted = Arrays.copyOf(iv, iv.length + content.length);
            System.arraycopy(content, 0, encrypted, iv.length, content.length);
            return encrypted;
        }

        @Override public byte[] decrypt(byte[] encrypted) throws GeneralSecurityException {
            if (encrypted.length < IV_BYTES + 16) throw new GeneralSecurityException("Invalid ciphertext");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, encrypted, 0, IV_BYTES));
            return cipher.doFinal(encrypted, IV_BYTES, encrypted.length - IV_BYTES);
        }

        private static SecretKey key(boolean create) throws GeneralSecurityException {
            try {
                KeyStore store = KeyStore.getInstance("AndroidKeyStore");
                store.load(null);
                if (store.containsAlias(KEY_ALIAS)) {
                    Key existing = store.getKey(KEY_ALIAS, null);
                    if (!(existing instanceof SecretKey)) throw new GeneralSecurityException("Invalid settings key");
                    return (SecretKey) existing;
                }
                if (!create) throw new GeneralSecurityException("Settings key unavailable");
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build());
                return generator.generateKey();
            } catch (IOException unavailable) {
                throw new GeneralSecurityException("Settings key unavailable");
            }
        }
    }
}
