/*
 * Telegram X - Nicegram Edition
 * Folder Batch Sender (Масова відправка / пересилання по папках із затримкою)
 */

package org.thunderdog.challegram.tool;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class FolderBatchSender {
  private static final long DEFAULT_DELAY_MS = 2000L; // 2 секунди між групами

  private final Client tdClient;
  private final List<Long> targetChatIds = new ArrayList<>();
  private final long delayMs;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private final AtomicBoolean isRunning = new AtomicBoolean(false);
  private final AtomicBoolean isCancelled = new AtomicBoolean(false);

  // Дані для відправки: або текст, або пересилання існуючого повідомлення
  @Nullable private String textToSend;
  @Nullable private Long forwardFromChatId;
  @Nullable private long[] forwardMessageIds;

  private ProgressCallback callback;

  public interface ProgressCallback {
    void onStarted(int totalCount);
    void onProgress(int current, int total, long currentChatId);
    void onFloodWait(int secondsRemaining);
    void onCompleted(int totalSent, int totalFailed);
    void onCancelled(int sentSoFar);
  }

  public FolderBatchSender(@NonNull Client tdClient, @NonNull List<Long> chatIds, long delayMs) {
    this.tdClient = tdClient;
    this.targetChatIds.addAll(chatIds);
    this.delayMs = delayMs > 0 ? delayMs : DEFAULT_DELAY_MS;
  }

  public FolderBatchSender(@NonNull Client tdClient, @NonNull List<Long> chatIds) {
    this(tdClient, chatIds, DEFAULT_DELAY_MS);
  }

  public void setMessageText(@NonNull String text) {
    this.textToSend = text;
    this.forwardFromChatId = null;
    this.forwardMessageIds = null;
  }

  public void setForwardMessage(long fromChatId, long[] messageIds) {
    this.forwardFromChatId = fromChatId;
    this.forwardMessageIds = messageIds;
    this.textToSend = null;
  }

  public void setCallback(ProgressCallback callback) {
    this.callback = callback;
  }

  public void start() {
    if (isRunning.compareAndSet(false, true)) {
      isCancelled.set(false);
      executor.execute(this::executeBatch);
    }
  }

  public void cancel() {
    isCancelled.set(true);
  }

  public boolean isRunning() {
    return isRunning.get();
  }

  private void executeBatch() {
    final int total = targetChatIds.size();
    int successCount = 0;
    int failCount = 0;

    notifyStarted(total);

    for (int i = 0; i < total; i++) {
      if (isCancelled.get()) {
        isRunning.set(false);
        notifyCancelled(successCount);
        return;
      }

      final long chatId = targetChatIds.get(i);
      final int currentIndex = i + 1;
      notifyProgress(currentIndex, total, chatId);

      // Виконання запиту відправки
      boolean sendSuccess = sendOrForward(chatId);
      if (sendSuccess) {
        successCount++;
      } else {
        failCount++;
      }

      // Затримка між відправками (якщо не останній чат)
      if (i < total - 1 && !isCancelled.get()) {
        try {
          Thread.sleep(delayMs);
        } catch (InterruptedException e) {
          if (isCancelled.get()) break;
        }
      }
    }

    isRunning.set(false);
    notifyCompleted(successCount, failCount);
  }

  private boolean sendOrForward(long targetChatId) {
    final AtomicBoolean done = new AtomicBoolean(false);
    final AtomicBoolean success = new AtomicBoolean(false);

    Client.ResultHandler handler = result -> {
      if (result instanceof TdApi.Messages || result instanceof TdApi.Message) {
        success.set(true);
      } else if (result instanceof TdApi.Error) {
        TdApi.Error err = (TdApi.Error) result;
        // Захист від FloodWait: якщо Telegram просить почекати
        if (err.code == 429 && err.message.startsWith("FLOOD_WAIT_")) {
          try {
            int waitSec = Integer.parseInt(err.message.replace("FLOOD_WAIT_", ""));
            notifyFloodWait(waitSec);
            Thread.sleep((waitSec + 1) * 1000L);
            // Повторюємо спробу після очікування
            sendOrForward(targetChatId);
            return;
          } catch (Exception ignored) {}
        }
      }
      done.set(true);
    };

    if (forwardFromChatId != null && forwardMessageIds != null) {
      // Пересилання повідомлення
      TdApi.ForwardMessages req = new TdApi.ForwardMessages(
          targetChatId,
          0, // messageThreadId
          forwardFromChatId,
          forwardMessageIds,
          null, // options
          false, // sendCopy
          false, // removeCaption
          false  // onlyLargeMedia
      );
      tdClient.send(req, handler);
    } else if (textToSend != null) {
      // Відправка текстового повідомлення
      TdApi.FormattedText formattedText = new TdApi.FormattedText(textToSend, new TdApi.TextEntity[0]);
      TdApi.InputMessageText content = new TdApi.InputMessageText(formattedText, null, false);
      TdApi.SendMessage req = new TdApi.SendMessage(
          targetChatId,
          0, // messageThreadId
          null, // replyTo
          null, // options
          null, // replyMarkup
          content
      );
      tdClient.send(req, handler);
    } else {
      return false;
    }

    // Очікуємо відповіді TDLib (до 10 секунд)
    long start = System.currentTimeMillis();
    while (!done.get() && (System.currentTimeMillis() - start) < 10000) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        break;
      }
    }

    return success.get();
  }

  private void notifyStarted(int total) {
    if (callback != null) {
      mainHandler.post(() -> callback.onStarted(total));
    }
  }

  private void notifyProgress(int current, int total, long chatId) {
    if (callback != null) {
      mainHandler.post(() -> callback.onProgress(current, total, chatId));
    }
  }

  private void notifyFloodWait(int seconds) {
    if (callback != null) {
      mainHandler.post(() -> callback.onFloodWait(seconds));
    }
  }

  private void notifyCompleted(int success, int fail) {
    if (callback != null) {
      mainHandler.post(() -> callback.onCompleted(success, fail));
    }
  }

  private void notifyCancelled(int sent) {
    if (callback != null) {
      mainHandler.post(() -> callback.onCancelled(sent));
    }
  }
}
