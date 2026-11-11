package com.xa.mass.android.workerdemo;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ContextWrapper;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33,
        shadows = AndroidConnectionSettingsStoreTest.PosixAtomicFileShadow.class)
public class AndroidConnectionSettingsStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final AndroidConnectionSettings settings = AndroidConnectionSettings.authenticated(
            "https://workers.example", "host-a", "unique-private-secret-秘密");

    @Test public void atomicFilePublishesAndReadsKnownBytes() throws Exception {
        File file = temporary.newFile("atomic.enc");
        AndroidConnectionSettingsStore.AtomicSettingsFile atomic =
                new AndroidConnectionSettingsStore.AtomicSettingsFile(file);
        byte[] bytes = "atomic-boundary".getBytes(StandardCharsets.UTF_8);
        atomic.write(bytes);
        assertArrayEquals(bytes, atomic.read());
    }

    @Test public void constructingTheStoreDoesNotReadFilesOrAccessKeystore() {
        AndroidConnectionSettingsStore store = new AndroidConnectionSettingsStore(new ContextWrapper(null) {
            @Override public File getNoBackupFilesDir() { throw new IllegalStateException("raw storage detail"); }
        });
        IllegalStateException error = assertThrows(IllegalStateException.class, store::load);
        assertEquals("Unable to read saved connection settings", error.getMessage());
        assertNull(error.getCause());
    }

    @Test public void absentOverrideDoesNotCreateAKeyOrAFile() throws Exception {
        File file = new File(temporary.getRoot(), "missing.enc");
        AndroidConnectionSettingsStore store = new AndroidConnectionSettingsStore(
                new AndroidConnectionSettingsStore.AtomicSettingsFile(file), unavailableCipher());
        assertNull(store.load());
        assertFalse(file.exists());
    }

    @Test public void encryptedRoundTripSurvivesStoreRecreationWithoutPlaintext() throws Exception {
        File file = temporary.newFile("connection.enc");
        TestCipher cipher = new TestCipher();
        AndroidConnectionSettingsStore store = store(file, cipher);
        store.save(settings);
        byte[] first = Files.readAllBytes(file.toPath());
        String bytes = new String(first, StandardCharsets.UTF_8);
        assertFalse(bytes.contains("workers.example"));
        assertFalse(bytes.contains("host-a"));
        assertFalse(bytes.contains("unique-private-secret"));
        assertTrue(settings.sameConnection(store(file, cipher).load()));
        store.save(settings);
        assertFalse("GCM must use a fresh nonce", Arrays.equals(first, Files.readAllBytes(file.toPath())));
        assertTrue(settings.sameConnection(store.load()));
    }

    @Test public void corruptedOrTruncatedCiphertextFailsClosed() throws Exception {
        File file = temporary.newFile("connection.enc");
        AndroidConnectionSettingsStore store = store(file, new TestCipher());
        store.save(settings);
        byte[] bytes = Files.readAllBytes(file.toPath());
        bytes[bytes.length - 1] ^= 1;
        Files.write(file.toPath(), bytes);
        IllegalStateException failure = assertThrows(IllegalStateException.class, store::load);
        assertEquals("Unable to read saved connection settings", failure.getMessage());
        assertNull(failure.getCause());
        Files.write(file.toPath(), new byte[0]);
        assertThrows(IllegalStateException.class, store::load);
    }

    @Test public void unavailableKeyDoesNotFallBackOrReplaceTheSavedFile() throws Exception {
        File file = temporary.newFile("connection.enc");
        store(file, new TestCipher()).save(settings);
        byte[] before = Files.readAllBytes(file.toPath());
        AndroidConnectionSettingsStore failing = store(file, unavailableCipher());
        assertThrows(IllegalStateException.class, failing::load);
        assertThrows(IllegalStateException.class, () -> failing.save(settings));
        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
    }

    @Test public void failedPublicationRestoresTheCompletePreviousCiphertext() throws Exception {
        File file = temporary.newFile("connection.enc");
        TestCipher cipher = new TestCipher();
        store(file, cipher).save(settings);
        byte[] before = Files.readAllBytes(file.toPath());
        AtomicFile failing = new AtomicFile(file) {
            @Override public void finishWrite(FileOutputStream stream) {
                throw new IllegalStateException("provider message containing unique-private-secret");
            }
        };
        AndroidConnectionSettingsStore store = new AndroidConnectionSettingsStore(
                new AndroidConnectionSettingsStore.AtomicSettingsFile(failing), cipher);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> store.save(AndroidConnectionSettings.authenticated("https://new.example", "new-host", "new-secret")));
        assertEquals("Unable to save connection settings", error.getMessage());
        assertNull(error.getCause());
        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
        assertTrue(settings.sameConnection(store(file, cipher).load()));
    }

    @Test public void silentlyUnpublishedAtomicWriteCannotReportSuccess() throws Exception {
        File file = temporary.newFile("connection.enc");
        TestCipher cipher = new TestCipher();
        store(file, cipher).save(settings);
        byte[] before = Files.readAllBytes(file.toPath());
        AtomicFile silentlyFailing = new AtomicFile(file) {
            @Override public void finishWrite(FileOutputStream stream) {
                try { stream.close(); } catch (IOException ignored) { }
            }
        };
        AndroidConnectionSettingsStore store = new AndroidConnectionSettingsStore(
                new AndroidConnectionSettingsStore.AtomicSettingsFile(silentlyFailing), cipher);
        assertThrows(IllegalStateException.class,
                () -> store.save(AndroidConnectionSettings.authenticated("https://new.example", "new-host", "new-secret")));
        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
    }

    @Test public void resetWorksWithAnUnavailableKeyAndDoesNotTouchOtherAppData() throws Exception {
        File file = temporary.newFile("connection.enc");
        File identity = temporary.newFile("retained-identity");
        Files.write(identity.toPath(), "unchanged".getBytes(StandardCharsets.UTF_8));
        store(file, new TestCipher()).save(settings);
        AndroidConnectionSettingsStore failing = store(file, unavailableCipher());
        failing.clear();
        assertNull(failing.load());
        assertEquals("unchanged", new String(Files.readAllBytes(identity.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void resetPublicationFailureKeepsSavedConnection() throws Exception {
        File file = temporary.newFile("connection.enc");
        TestCipher cipher = new TestCipher();
        store(file, cipher).save(settings);
        AtomicFile failing = new AtomicFile(file) {
            @Override public void finishWrite(FileOutputStream stream) { throw new IllegalStateException("write failed"); }
        };
        AndroidConnectionSettingsStore store = new AndroidConnectionSettingsStore(
                new AndroidConnectionSettingsStore.AtomicSettingsFile(failing), cipher);
        assertThrows(IllegalStateException.class, store::clear);
        assertTrue(settings.sameConnection(store(file, cipher).load()));
    }

    @Test public void uncommittedAtomicWriteCannotReplaceAConfirmedOverride() throws Exception {
        File file = temporary.newFile("connection.enc");
        TestCipher cipher = new TestCipher();
        store(file, cipher).save(settings);
        AtomicFile interrupted = new AtomicFile(file);
        FileOutputStream output = interrupted.startWrite();
        output.write("incomplete".getBytes(StandardCharsets.UTF_8));
        output.close();
        assertTrue(settings.sameConnection(store(file, cipher).load()));
    }

    @Test public void oversizedInputAndSavedFileAreRejectedWithinTheBound() throws Exception {
        File file = temporary.newFile("connection.enc");
        TestCipher cipher = new TestCipher();
        AndroidConnectionSettingsStore store = store(file, cipher);
        store.save(settings);
        byte[] before = Files.readAllBytes(file.toPath());
        AndroidConnectionSettings huge = AndroidConnectionSettings.authenticated(
                "https://workers.example/" + "x".repeat(AndroidConnectionSettingsStore.MAX_FILE_BYTES), "host", "secret");
        assertThrows(IllegalStateException.class, () -> store.save(huge));
        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
        Files.write(file.toPath(), new byte[AndroidConnectionSettingsStore.MAX_FILE_BYTES + 1]);
        assertThrows(IllegalStateException.class, store::load);
    }

    @Test public void anonymousSettingsCannotBecomeASavedOverride() throws Exception {
        File file = new File(temporary.getRoot(), "connection.enc");
        AndroidConnectionSettingsStore store = store(file, new TestCipher());
        assertThrows(IllegalArgumentException.class,
                () -> store.save(AndroidConnectionSettings.defaults(URI.create("http://127.0.0.1:18182"))));
        assertFalse(file.exists());
    }

    private static AndroidConnectionSettingsStore store(File file, AndroidConnectionSettingsStore.SecretCipher cipher) {
        return new AndroidConnectionSettingsStore(new AndroidConnectionSettingsStore.AtomicSettingsFile(file), cipher);
    }

    private static AndroidConnectionSettingsStore.SecretCipher unavailableCipher() {
        return new AndroidConnectionSettingsStore.SecretCipher() {
            @Override public byte[] encrypt(byte[] plaintext) throws GeneralSecurityException {
                throw new GeneralSecurityException("unavailable unique-private-secret");
            }
            @Override public byte[] decrypt(byte[] encrypted) throws GeneralSecurityException {
                throw new GeneralSecurityException("unavailable unique-private-secret");
            }
        };
    }

    private static final class TestCipher implements AndroidConnectionSettingsStore.SecretCipher {
        private final SecretKey key;
        TestCipher() throws GeneralSecurityException {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256); key = generator.generateKey();
        }
        @Override public byte[] encrypt(byte[] plaintext) throws GeneralSecurityException {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] content = cipher.doFinal(plaintext);
            byte[] encoded = Arrays.copyOf(cipher.getIV(), 12 + content.length);
            System.arraycopy(content, 0, encoded, 12, content.length);
            return encoded;
        }
        @Override public byte[] decrypt(byte[] encrypted) throws GeneralSecurityException {
            if (encrypted.length < 28) throw new GeneralSecurityException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, encrypted, 0, 12));
            return cipher.doFinal(encrypted, 12, encrypted.length - 12);
        }
    }

    /** Robolectric runs Android code on the host OS; Windows File.renameTo cannot replace a file. */
    @Implements(value = AtomicFile.class, callThroughByDefault = true)
    public static final class PosixAtomicFileShadow {
        @Implementation protected static void rename(File source, File target) {
            try {
                // Preserve Android's POSIX atomic replacement, never a delete/copy fallback.
                Files.move(source.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                android.util.Log.e("AtomicFile", "Atomic replacement failed", failure);
            }
        }
    }
}
