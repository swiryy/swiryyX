/*
 * Telegram X - Nicegram Edition
 * Folder Batch Sender (Масова відправка / пересилання по папках із затримкою)
 *
 * Інтеграція: додати виклик з UI контролера папок (ChatFolderController)
 * після вибору повідомлень та натискання кнопки "Відправити всім у папці".
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Асинхронний диспетчер черги масової відправки повідомлень
 * у список чатів із захистом від FloodWait.
 *
 * Використання:
 * <pre>
 *   FolderBatchSender sender = new FolderBatchSender(client, chatIds, 2000);
 *   sender.setMessageText("Привіт!");
 *   sender.setCallback(progressCallback);
 *   sender.start();
 * </pre>
 */
public class FolderBatchSender {
  private static final long DEFAULT_DELAY_MS = 2000L;
  private static final long RESPONSE_TIMEOUT_MS = 15000L;
  private static final int MAX_FLOOD_RETRIES = 3;

  private final Client tdClient;
  private final List<Long> targetChatIds;
  private final long delayMs;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private final AtomicBoolean isRunning = new AtomicBoolean(false);
  private final AtomicBoolean isCancelled = new AtomicBoolean(false);

  @Nullable private String textToSend;
  @Nullable private Long forwardFromChatId;
  @Nullable private long[] forwardMessageIds;

  @Nullable private ProgressCallback callback;

  public interface ProgressCallback {
    /** Викликається на початку масової відправки */
    void onStarted(int totalCount);
    /** Поточний прогрес: відправлено current з total */
    void onProgress(int current, int total, long currentChatId);
    /** Telegram повернув FloodWait, чекаємо secondsRemaining сек */
    void onFloodWait(int secondsRemaining);
    /** Відправка завершена */
    void onCompleted(int totalSent, int totalFailed);
    /** Відправка скасована користувачем */
    void onCancelled(int sentSoFar);
    /** Помилка при відправці в конкретний чат */
    void onError(long chatId, String errorMessage);
  }

  public FolderBatchSender(@NonNull Client tdClient, @NonNull List<Long> chatIds, long delayMs) {
    this.tdClient = tdClient;
    this.targetChatIds = new ArrayList<>(chatIds); // defensive copy
    this.delayMs = delayMs > 0 ? delayMs : DEFAULT_DELAY_MS;
  }

  public FolderBatchSender(@NonNull Client tdClient, @NonNull List<Long> chatIds) {
    this(tdClient, chatIds, DEFAULT_DELAY_MS);
  }

  /** Встановити текст повідомлення для відправки */
  public void setMessageText(@NonNull String text) {
    this.textToSend = text;
    this.forwardFromChatId = null;
    this.forwardMessageIds = null;
  }

  /** Встановити повідомлення для пересилання */
  public void setForwardMessage(long fromChatId, @NonNull long[] messageIds) {
    this.forwardFromChatId = fromChatId;
    this.forwardMessageIds = messageIds.clone();
    this.textToSend = null;
  }

  public void setCallback(@Nullable ProgressCallback callback) {
    this.callback = callback;
  }

  /** Розпочати масову відправку (async) */
  public void start() {
    if (isRunning.compareAndSet(false, true)) {
      isCancelled.set(false);
      executor.execute(this::executeBatch);
    }
  }

  /** Скасувати відправку */
  public void cancel() {
    isCancelled.set(true);
  }

  public boolean isRunning() {
    return isRunning.get();
  }

  /** Звільнити ресурси (executor) після завершення */
  public void shutdown() {
    cancel();
    executor.shutdownNow();
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

      boolean sendSuccess = sendWithFloodProtection(chatId);
      if (sendSuccess) {
        successCount++;
      } else {
        failCount++;
      }

      // Затримка між відправками (не для останнього чату)
      if (i < total - 1 && !isCancelled.get()) {
        try {
          Thread.sleep(delayMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          if (isCancelled.get()) break;
        }
      }
    }

    isRunning.set(false);
    notifyCompleted(successCount, failCount);
  }

  /**
   * Відправка з автоматичним retry при FloodWait.
   * Замість рекурсії використовується цикл, щоб уникнути stack overflow.
   */
  private boolean sendWithFloodProtection(long targetChatId) {
    for (int attempt = 0; attempt < MAX_FLOOD_RETRIES; attempt++) {
      if (isCancelled.get()) return false;

      SendResult result = sendOrForward(targetChatId);

      switch (result.status) {
        case SUCCESS:
          return true;
        case FLOOD_WAIT:
          int waitSec = result.floodWaitSeconds;
          notifyFloodWait(waitSec);
          try {
            Thread.sleep((waitSec + 1) * 1000L);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
          }
          break; // retry
        case ERROR:
          notifyError(targetChatId, result.errorMessage);
          return false;
      }
    }
    notifyError(targetChatId, "Flood protection: exceeded max retries");
    return false;
  }

  private SendResult sendOrForward(long targetChatId) {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<SendResult> resultRef = new AtomicReference<>(
        new SendResult(SendStatus.ERROR, "Timeout waiting for TDLib response", 0)
    );

    Client.ResultHandler handler = result -> {
      if (result instanceof TdApi.Messages || result instanceof TdApi.Message) {
        resultRef.set(new SendResult(SendStatus.SUCCESS, null, 0));
      } else if (result instanceof TdApi.Error) {
        TdApi.Error err = (TdApi.Error) result;
        if (err.code == 429 && err.message != null && err.message.startsWith("FLOOD_WAIT_")) {
          try {
            int waitSec = Integer.parseInt(err.message.replace("FLOOD_WAIT_", ""));
            resultRef.set(new SendResult(SendStatus.FLOOD_WAIT, err.message, waitSec));
          } catch (NumberFormatException e) {
            resultRef.set(new SendResult(SendStatus.ERROR, err.message, 0));
          }
        } else {
          resultRef.set(new SendResult(SendStatus.ERROR,
              err.code + ": " + (err.message != null ? err.message : "Unknown error"), 0));
        }
      }
      latch.countDown();
    };

    if (forwardFromChatId != null && forwardMessageIds != null) {
      // Пересилання —  конструктор TGX ForwardMessages:
      // ForwardMessages(chatId, fromChatId, messageIds, options, sendCopy, removeCaption)
      TdApi.ForwardMessages req = new TdApi.ForwardMessages();
      req.chatId = targetChatId;
      req.fromChatId = forwardFromChatId;
      req.messageIds = forwardMessageIds;
      // options, sendCopy, removeCaption — за замовчуванням null/false
      tdClient.send(req, handler);
    } else if (textToSend != null) {
      // Відправка тексту — конструктор TGX SendMessage:
      // SendMessage(chatId, messageThreadId, replyTo, options, replyMarkup, content)
      TdApi.FormattedText formattedText = new TdApi.FormattedText(textToSend, new TdApi.TextEntity[0]);
      TdApi.InputMessageText content = new TdApi.InputMessageText(formattedText, null, false);
      TdApi.SendMessage req = new TdApi.SendMessage();
      req.chatId = targetChatId;
      req.inputMessageContent = content;
      tdClient.send(req, handler);
    } else {
      return new SendResult(SendStatus.ERROR, "No message content specified", 0);
    }

    try {
      if (!latch.await(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        return new SendResult(SendStatus.ERROR, "Timeout", 0);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new SendResult(SendStatus.ERROR, "Interrupted", 0);
    }

    return resultRef.get();
  }

  // --- Internal result wrapper ---

  private enum SendStatus { SUCCESS, FLOOD_WAIT, ERROR }

  private static class SendResult {
    final SendStatus status;
    @Nullable final String errorMessage;
    final int floodWaitSeconds;

    SendResult(SendStatus status, @Nullable String errorMessage, int floodWaitSeconds) {
      this.status = status;
      this.errorMessage = errorMessage;
      this.floodWaitSeconds = floodWaitSeconds;
    }
  }

  // --- Callback notifications (Main Thread) ---

  private void notifyStarted(int total) {
    if (callback != null) mainHandler.post(() -> callback.onStarted(total));
  }

  private void notifyProgress(int current, int total, long chatId) {
    if (callback != null) mainHandler.post(() -> callback.onProgress(current, total, chatId));
  }

  private void notifyFloodWait(int seconds) {
    if (callback != null) mainHandler.post(() -> callback.onFloodWait(seconds));
  }

  private void notifyCompleted(int success, int fail) {
    if (callback != null) mainHandler.post(() -> callback.onCompleted(success, fail));
  }

  private void notifyCancelled(int sent) {
    if (callback != null) mainHandler.post(() -> callback.onCancelled(sent));
  }

  private void notifyError(long chatId, @Nullable String msg) {
    if (callback != null) mainHandler.post(() -> callback.onError(chatId, msg != null ? msg : "Unknown error"));
  }
}
