/*
 * Telegram X - Nicegram Edition
 * Hidden Chats Manager (Приховані чати з захистом PIN-кодом)
 */

package org.thunderdog.challegram.tool;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class HiddenChatsManager {
  private static final String PREF_NAME = "nicegram_hidden_chats";
  private static final String KEY_PIN_HASH = "pin_hash";
  private static final String KEY_HIDDEN_CHAT_IDS = "hidden_chat_ids";

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
    this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    loadHiddenChats();
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

  public boolean hasPin() {
    return prefs.contains(KEY_PIN_HASH);
  }

  public boolean setPin(@NonNull String pin) {
    if (pin.length() < 4) return false;
    String hash = hashString(pin);
    if (hash != null) {
      prefs.edit().putString(KEY_PIN_HASH, hash).apply();
      return true;
    }
    return false;
  }

  public boolean verifyAndUnlock(@NonNull String pin) {
    String storedHash = prefs.getString(KEY_PIN_HASH, null);
    if (storedHash == null) {
      // No pin set, direct unlock
      isUnlocked = true;
      notifyStateChanged();
      return true;
    }
    String inputHash = hashString(pin);
    if (storedHash.equals(inputHash)) {
      isUnlocked = true;
      notifyStateChanged();
      return true;
    }
    return false;
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

  public boolean isChatHidden(long chatId) {
    return !isUnlocked && hiddenChatIds.contains(chatId);
  }

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

  public int getHiddenChatsCount() {
    return hiddenChatIds.size();
  }

  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  public void removeListener(Listener listener) {
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

  @Nullable
  private static String hashString(@NonNull String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes());
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      return null;
    }
  }
}
