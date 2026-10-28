package dev.tubescribe.transcribe;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores Groq API keys encrypted with a non-exportable Android Keystore key
 * (AES-256/GCM) under {@code filesDir/groq-keys.dat}.
 *
 * <p>The encryption key lives in the hardware-backed keystore where available,
 * so the stored blob cannot be read off the device even with root access to the
 * app's files. Users can enter any number of keys; the transcriber tries them
 * in order and falls over to the next one when a key is rate-limited or
 * rejected.</p>
 */
public final class GroqKeyStore {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "tubescribe_groq_keys";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private static File file = new File("groq-keys.dat"); // replaced by init()

    private GroqKeyStore() {
    }

    /** Called once from the app with the private directory to store keys in. */
    public static synchronized void init(final File baseDir) {
        file = new File(baseDir, "groq-keys.dat");
    }

    /** @return the configured keys in order; empty when none have been saved yet. */
    public static synchronized List<String> keys() {
        if (!file.isFile()) {
            return List.of();
        }
        try {
            final byte[] blob = Base64.decode(Files.readAllBytes(file), Base64.NO_WRAP);
            final byte[] plain = decrypt(blob);
            final List<String> keys = new ArrayList<>();
            for (final String line : new String(plain, StandardCharsets.UTF_8).split("\r\n|\n")) {
                final String value = line.strip();
                if (!value.isEmpty() && !value.startsWith("#")) {
                    keys.add(value);
                }
            }
            return keys;
        } catch (final Exception e) {
            throw new IllegalStateException(
                    "Cannot read the Groq key store: " + e.getMessage(), e);
        }
    }

    public static synchronized void save(final List<String> keys) throws IOException {
        final StringBuilder text = new StringBuilder();
        for (final String key : keys) {
            final String value = key == null ? "" : key.strip();
            if (!value.isEmpty()) {
                text.append(value).append('\n');
            }
        }
        final byte[] blob = encrypt(text.toString().getBytes(StandardCharsets.UTF_8));
        final File temp = new File(file.getParentFile(), file.getName() + ".part");
        Files.write(temp.toPath(), Base64.encode(blob, Base64.NO_WRAP));
        Files.move(temp.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static synchronized boolean isEmpty() {
        return keys().isEmpty();
    }

    // ----------------------------------------------------------------- crypto

    private static byte[] encrypt(final byte[] plain) throws IOException {
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(true));
            final byte[] iv = cipher.getIV();
            final byte[] ciphertext = cipher.doFinal(plain);
            final byte[] out = new byte[GCM_IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, GCM_IV_BYTES);
            System.arraycopy(ciphertext, 0, out, GCM_IV_BYTES, ciphertext.length);
            return out;
        } catch (final Exception e) {
            throw new IOException("Key encryption failed: " + e.getMessage(), e);
        }
    }

    private static byte[] decrypt(final byte[] blob) throws Exception {
        if (blob.length <= GCM_IV_BYTES) {
            throw new IOException("Key store is corrupt (too short).");
        }
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        final GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES);
        cipher.init(Cipher.DECRYPT_MODE, secretKey(false), spec);
        return cipher.doFinal(blob, GCM_IV_BYTES, blob.length - GCM_IV_BYTES);
    }

    private static SecretKey secretKey(final boolean createIfMissing) throws Exception {
        final KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        final KeyStore.SecretKeyEntry entry =
                (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
        if (entry != null) {
            return entry.getSecretKey();
        }
        if (!createIfMissing) {
            throw new IOException("The encryption key is missing from the Android keystore.");
        }
        final KeyGenerator generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
