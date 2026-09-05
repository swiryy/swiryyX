/*
 * Telegram X - Nicegram Edition
 * Hidden Chats Manager (Приховані чати з захистом PIN-кодом)
 *
 * Безпека: PIN хешується з випадковою сіллю через SHA-256.
 * Зберігається в EncryptedSharedPreferences (якщо доступно)
 * з fallback на звичайні SharedPreferences.
 */

package org.thunderdog.challegram.tool;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Менеджер прихованих чатів. Дозволяє приховати вибрані чати зі списку,
 * пошуку та лічильників. Доступ до прихованих чатів захищений PIN-кодом (SHA-256 + salt).
 *
 * <h3>Інтеграція з Telegram X:</h3>
 * <ol>
 *   <li>У {@code ChatsController} або {@code MainController}: фільтрувати список чатів
 *       через {@link #isChatHidden(long)} перед відображенням.</li>
 *   <li>У контекстному меню чату: додати пункт "Приховати чат" з викликом
 *       {@link #toggleHideChat(long)}.</li>
 *   <li>Розблокування: показати діалог PIN при довгому тапі по заголовку або
 *       при введенні спеціального коду у пошук.</li>
 * </ol>
 */
public class HiddenChatsManager {
  private static final String PREF_NAME = "nicegram_hidden_chats";
  private static final String KEY_PIN_HASH = "pin_hash";
  private static final String KEY_PIN_SALT = "pin_salt";
  private static final String KEY_HIDDEN_CHAT_IDS = "hidden_chat_ids";
  private static final int MIN_PIN_LENGTH = 4;
  private static final int SALT_BYTES = 16;

  private static volatile HiddenChatsManager instance;

  private final SharedPreferences prefs;
  private final Set<Long> hiddenChatIds = new CopyOnWriteArraySet<>();
  private boolean isUnlocked = false;
  private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

  public interface Listener {
    void onHiddenStateChanged(boolean isUnlocked);
    void onHiddenChatsListChanged();
  }

  public static HiddenChatsManager getInstance(@NonNull Context context) {
    if (instance == null) {
      synchronized (HiddenChatsManager.class) {
        if (instance == null) {
          instance = new HiddenChatsManager(context.getApplicationContext());
        }
      }
    }
    return instance;
  }

  private HiddenChatsManager(@NonNull Context context) {
    this.prefs = createSecurePrefs(context);
    loadHiddenChats();
  }

  /**
   * Спроба створити EncryptedSharedPreferences.
   * Якщо бібліотека недоступна — fallback на звичайні SharedPreferences.
   */
  private static SharedPreferences createSecurePrefs(@NonNull Context context) {
    try {
      // Спроба використання EncryptedSharedPreferences з AndroidX Security
      Class<?> espClass = Class.forName("androidx.security.crypto.EncryptedSharedPreferences");
      Class<?> masterKeyClass = Class.forName("androidx.security.crypto.MasterKey$Builder");

      Object masterKeyBuilder = masterKeyClass.getConstructor(Context.class).newInstance(context);
      java.lang.reflect.Method setSchemeMethod = masterKeyClass.getMethod("setKeyScheme",
          Class.forName("androidx.security.crypto.MasterKey$KeyScheme"));

      Object keySchemeAes = Class.forName("androidx.security.crypto.MasterKey$KeyScheme")
          .getField("AES256_GCM").get(null);
      masterKeyBuilder = setSchemeMethod.invoke(masterKeyBuilder, keySchemeAes);

      java.lang.reflect.Method buildMethod = masterKeyClass.getMethod("build");
      Object masterKey = buildMethod.invoke(masterKeyBuilder);

      Object prefEncScheme = Class.forName(
          "androidx.security.crypto.EncryptedSharedPreferences$PrefKeyEncryptionScheme")
          .getField("AES256_SIV").get(null);
      Object prefValScheme = Class.forName(
          "androidx.security.crypto.EncryptedSharedPreferences$PrefValueEncryptionScheme")
          .getField("AES256_GCM").get(null);

      java.lang.reflect.Method createMethod = espClass.getMethod("create",
          String.class, masterKey.getClass(),
          prefEncScheme.getClass(), prefValScheme.getClass());

      return (SharedPreferences) createMethod.invoke(null,
          PREF_NAME, masterKey, prefEncScheme, prefValScheme);
    } catch (Exception e) {
      // Fallback: звичайні SharedPreferences (все ще краще, ніж нічого)
      return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
  }

  private void loadHiddenChats() {
    Set<String> rawSet = prefs.getStringSet(KEY_HIDDEN_CHAT_IDS, Collections.emptySet());
    hiddenChatIds.clear();
    if (rawSet != null) {
      for (String s : rawSet) {
        try {
          hiddenChatIds.add(Long.parseLong(s));
        } catch (NumberFormatException ignored) {}
      }
    }
  }

  private void saveHiddenChats() {
    Set<String> rawSet = new HashSet<>();
    for (Long id : hiddenChatIds) {
      rawSet.add(String.valueOf(id));
    }
    prefs.edit().putStringSet(KEY_HIDDEN_CHAT_IDS, rawSet).apply();
    notifyListChanged();
  }

  // --- PIN Management ---

  public boolean hasPin() {
    return prefs.contains(KEY_PIN_HASH) && prefs.contains(KEY_PIN_SALT);
  }

  /**
   * Встановити або змінити PIN-код.
   * @param pin Мінімум {@value MIN_PIN_LENGTH} символи.
   * @return true, якщо PIN встановлено успішно.
   */
  public boolean setPin(@NonNull String pin) {
    if (pin.length() < MIN_PIN_LENGTH) return false;

    // Генерація випадкової солі
    byte[] saltBytes = new byte[SALT_BYTES];
    new SecureRandom().nextBytes(saltBytes);
    String salt = bytesToHex(saltBytes);

    String hash = hashWithSalt(pin, salt);
    if (hash != null) {
      prefs.edit()
          .putString(KEY_PIN_HASH, hash)
          .putString(KEY_PIN_SALT, salt)
          .apply();
      return true;
    }
    return false;
  }

  /**
   * Перевірити PIN і розблокувати приховані чати.
   * @return true, якщо PIN правильний або PIN не встановлений.
   */
  public boolean verifyAndUnlock(@NonNull String pin) {
    String storedHash = prefs.getString(KEY_PIN_HASH, null);
    String storedSalt = prefs.getString(KEY_PIN_SALT, null);

    if (storedHash == null || storedSalt == null) {
      // PIN не встановлений — пряме розблокування
      isUnlocked = true;
      notifyStateChanged();
      return true;
    }

    String inputHash = hashWithSalt(pin, storedSalt);
    if (storedHash.equals(inputHash)) {
      isUnlocked = true;
      notifyStateChanged();
      return true;
    }
    return false;
  }

  /** Скинути PIN (потрібен поточний PIN для підтвердження) */
  public boolean resetPin(@NonNull String currentPin) {
    if (!verifyPin(currentPin)) return false;
    prefs.edit()
        .remove(KEY_PIN_HASH)
        .remove(KEY_PIN_SALT)
        .apply();
    return true;
  }

  private boolean verifyPin(@NonNull String pin) {
    String storedHash = prefs.getString(KEY_PIN_HASH, null);
    String storedSalt = prefs.getString(KEY_PIN_SALT, null);
    if (storedHash == null || storedSalt == null) return true;
    String inputHash = hashWithSalt(pin, storedSalt);
    return storedHash.equals(inputHash);
  }

  public void lock() {
    if (isUnlocked) {
      isUnlocked = false;
      notifyStateChanged();
    }
  }

  public boolean isUnlocked() {
    return isUnlocked;
  }

  // --- Chat Visibility ---

  /**
   * Чи прихований чат зараз? Повертає true тільки якщо
   * чат у списку прихованих І менеджер заблокований.
   */
  public boolean isChatHidden(long chatId) {
    return !isUnlocked && hiddenChatIds.contains(chatId);
  }

  /** Чи позначений чат як прихований (незалежно від стану lock/unlock) */
  public boolean isChatMarkedAsHidden(long chatId) {
    return hiddenChatIds.contains(chatId);
  }

  public void toggleHideChat(long chatId) {
    if (hiddenChatIds.contains(chatId)) {
      hiddenChatIds.remove(chatId);
    } else {
      hiddenChatIds.add(chatId);
    }
    saveHiddenChats();
  }

  public void hideChat(long chatId) {
    if (hiddenChatIds.add(chatId)) {
      saveHiddenChats();
    }
  }

  public void unhideChat(long chatId) {
    if (hiddenChatIds.remove(chatId)) {
      saveHiddenChats();
    }
  }

  /** Отримати набір ID прихованих чатів (тільки для читання) */
  public Set<Long> getHiddenChatIds() {
    return Collections.unmodifiableSet(new HashSet<>(hiddenChatIds));
  }

  public int getHiddenChatsCount() {
    return hiddenChatIds.size();
  }

  // --- Listeners ---

  public void addListener(@NonNull Listener listener) {
    listeners.add(listener);
  }

  public void removeListener(@NonNull Listener listener) {
    listeners.remove(listener);
  }

  private void notifyStateChanged() {
    for (Listener l : listeners) {
      l.onHiddenStateChanged(isUnlocked);
    }
  }

  private void notifyListChanged() {
    for (Listener l : listeners) {
      l.onHiddenChatsListChanged();
    }
  }

  // --- Crypto Helpers ---

  @Nullable
  private static String hashWithSalt(@NonNull String input, @NonNull String salt) {
    return hashString(salt + input);
  }

  @Nullable
  private static String hashString(@NonNull String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return bytesToHex(digest);
    } catch (NoSuchAlgorithmException e) {
      return null;
    }
  }

  @NonNull
  private static String bytesToHex(@NonNull byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
