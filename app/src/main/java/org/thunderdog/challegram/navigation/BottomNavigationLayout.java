/*
 * Telegram X - Nicegram Edition
 * Bottom Navigation Layout (Нижня навігаційна панель у стилі iOS / Nicegram)
 *
 * Високопродуктивна кастомна View із відмальовкою на Canvas.
 * Підтримує іконки, анімації перемикання та бейджі непрочитаних повідомлень.
 *
 * Інтеграція: додати у MainController.java як дочірній View
 * до FrameLayout з gravity=BOTTOM. Заховати Drawer / NavigationDrawer
 * та перемикати контролери через onTabSelected.
 */

package org.thunderdog.challegram.navigation;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.tool.Screen;

/**
 * Нижня навігаційна панель у стилі iOS / Nicegram для Telegram X.
 *
 * <p>Замінює бічне меню (Drawer) на 4 вкладки внизу екрана:
 * Чати, Дзвінки, Контакти, Налаштування.</p>
 *
 * <p>Особливості:</p>
 * <ul>
 *   <li>Плавні анімації перемикання вкладок через {@link ValueAnimator}</li>
 *   <li>Червоні бейджі з лічильниками непрочитаних (до "99+")</li>
 *   <li>iOS-стиль іконок на Canvas (Path-based, без растрових ресурсів)</li>
 *   <li>Тактильний відгук при натисканні</li>
 *   <li>Повна підтримка динамічного тюнінгу теми через {@link #applyThemeColors}</li>
 * </ul>
 */
public class BottomNavigationLayout extends View {
  public static final int TAB_CHATS = 0;
  public static final int TAB_CALLS = 1;
  public static final int TAB_CONTACTS = 2;
  public static final int TAB_SETTINGS = 3;
  public static final int TAB_COUNT = 4;

  public interface OnTabSelectedListener {
    void onTabSelected(int tabIndex);
    void onTabReselected(int tabIndex);
  }

  private int selectedTab = TAB_CHATS;
  private int previousTab = -1;
  private final int[] unreadCounts = new int[TAB_COUNT];
  private final String[] tabTitles = new String[]{"Чати", "Дзвінки", "Контакти", "Налаштування"};

  // Анімація
  private float selectionFraction = 1f; // 0..1 анімація переходу
  private ValueAnimator tabAnimator;

  // Paints
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint separatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint backgroundPaint = new Paint();

  // Кольори (дефолт — Nicegram Dark)
  private int colorActive = 0xFF6C5CE7;    // Nicegram Purple
  private int colorInactive = 0xFF8E8E93;  // iOS Neutral Gray
  private int colorBackground = 0xFF1C1C1E;// iOS Dark Bar
  private int colorSeparator = 0xFF2C2C2E; // iOS Dark Separator
  private int colorBadge = 0xFFFF3B30;     // iOS Red Badge

  // Touch
  private int touchedTab = -1;

  @Nullable
  private OnTabSelectedListener listener;

  public BottomNavigationLayout(Context context) {
    super(context);
    init();
  }

  private void init() {
    // Текст під іконками
    textPaint.setTextSize(Screen.dp(10f));
    textPaint.setTextAlign(Paint.Align.CENTER);
    textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

    // Іконки
    iconPaint.setStyle(Paint.Style.STROKE);
    iconPaint.setStrokeWidth(Screen.dp(1.5f));
    iconPaint.setStrokeCap(Paint.Cap.ROUND);
    iconPaint.setStrokeJoin(Paint.Join.ROUND);

    // Бейджі
    badgeTextPaint.setTextSize(Screen.dp(10f));
    badgeTextPaint.setTextAlign(Paint.Align.CENTER);
    badgeTextPaint.setColor(0xFFFFFFFF);
    badgeTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

    separatorPaint.setStrokeWidth(Screen.dp(0.5f));

    // Accessibility
    setContentDescription("Bottom Navigation");
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  public void setOnTabSelectedListener(@Nullable OnTabSelectedListener listener) {
    this.listener = listener;
  }

  public void setSelectedTab(int tabIndex) {
    setSelectedTab(tabIndex, true);
  }

  public void setSelectedTab(int tabIndex, boolean animate) {
    if (tabIndex >= 0 && tabIndex < TAB_COUNT && selectedTab != tabIndex) {
      previousTab = selectedTab;
      selectedTab = tabIndex;
      if (animate) {
        animateSelection();
      } else {
        selectionFraction = 1f;
        invalidate();
      }
    }
  }

  public int getSelectedTab() {
    return selectedTab;
  }

  public void setBadgeCount(int tabIndex, int count) {
    if (tabIndex >= 0 && tabIndex < TAB_COUNT && unreadCounts[tabIndex] != count) {
      unreadCounts[tabIndex] = Math.max(0, count);
      invalidate();
    }
  }

  public void applyThemeColors(int active, int inactive, int background, int separator) {
    this.colorActive = active;
    this.colorInactive = inactive;
    this.colorBackground = background;
    this.colorSeparator = separator;
    invalidate();
  }

  public void setBadgeColor(int badgeColor) {
    this.colorBadge = badgeColor;
    invalidate();
  }

  // --- Animation ---

  private void animateSelection() {
    if (tabAnimator != null) tabAnimator.cancel();
    selectionFraction = 0f;
    tabAnimator = ValueAnimator.ofFloat(0f, 1f);
    tabAnimator.setDuration(200);
    tabAnimator.setInterpolator(new DecelerateInterpolator());
    tabAnimator.addUpdateListener(a -> {
      selectionFraction = (float) a.getAnimatedValue();
      invalidate();
    });
    tabAnimator.start();
  }

  // --- Measure & Draw ---

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int height = Screen.dp(56f); // Стандартна висота iOS Tab Bar
    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
  }

  @Override
  protected void onDraw(@NonNull Canvas canvas) {
    final int width = getWidth();
    final int height = getHeight();
    if (width == 0 || height == 0) return;

    // 1. Фон
    backgroundPaint.setColor(colorBackground);
    canvas.drawRect(0, 0, width, height, backgroundPaint);

    // 2. Верхній розділювач
    separatorPaint.setColor(colorSeparator);
    canvas.drawLine(0, 0.5f, width, 0.5f, separatorPaint);

    // 3. Відмальовка табів
    final float tabWidth = (float) width / TAB_COUNT;
    final float iconCenterY = height * 0.32f;
    final float textY = height - Screen.dp(6f);

    for (int i = 0; i < TAB_COUNT; i++) {
      final float tabCenterX = tabWidth * i + (tabWidth / 2f);
      final boolean isSelected = (i == selectedTab);
      final boolean wasPrevious = (i == previousTab);

      // Обчислення кольору з анімацією
      int itemColor;
      if (isSelected) {
        itemColor = blendColor(colorInactive, colorActive, selectionFraction);
      } else if (wasPrevious) {
        itemColor = blendColor(colorActive, colorInactive, selectionFraction);
      } else {
        itemColor = colorInactive;
      }

      // Іконка
      drawTabIcon(canvas, i, tabCenterX, iconCenterY, itemColor, isSelected);

      // Текст
      textPaint.setColor(itemColor);
      textPaint.setFakeBoldText(isSelected);
      canvas.drawText(tabTitles[i], tabCenterX, textY, textPaint);

      // Бейдж непрочитаних
      if (unreadCounts[i] > 0) {
        drawBadge(canvas, unreadCounts[i], tabCenterX, iconCenterY);
      }
    }
  }

  /**
   * Малює іконку для кожної вкладки через Path (векторні іконки).
   * Це дозволяє змінювати колір іконки під тему без растрових ресурсів.
   */
  private void drawTabIcon(Canvas canvas, int tabIndex, float cx, float cy,
                           int color, boolean selected) {
    iconPaint.setColor(color);
    float s = Screen.dp(11f); // half-size of icon

    canvas.save();
    canvas.translate(cx, cy);

    switch (tabIndex) {
      case TAB_CHATS:
        // Іконка "бульбашка повідомлення" (Chat bubble)
        iconPaint.setStyle(selected ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        RectF bubbleRect = new RectF(-s, -s * 0.85f, s, s * 0.65f);
        float r = Screen.dp(4f);
        canvas.drawRoundRect(bubbleRect, r, r, iconPaint);
        // Хвостик бульбашки
        Path tail = new Path();
        tail.moveTo(-s * 0.3f, s * 0.65f);
        tail.lineTo(-s * 0.5f, s);
        tail.lineTo(s * 0.1f, s * 0.65f);
        tail.close();
        canvas.drawPath(tail, iconPaint);
        iconPaint.setStyle(Paint.Style.STROKE);
        break;

      case TAB_CALLS:
        // Іконка "телефонна трубка" (Phone)
        iconPaint.setStyle(selected ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        Path phone = new Path();
        phone.moveTo(-s * 0.8f, -s * 0.4f);
        phone.quadTo(-s * 0.8f, -s * 0.8f, -s * 0.4f, -s * 0.8f);
        phone.lineTo(-s * 0.1f, -s * 0.4f);
        phone.lineTo(-s * 0.3f, -s * 0.1f);
        phone.lineTo(s * 0.1f, s * 0.3f);
        phone.lineTo(s * 0.4f, s * 0.1f);
        phone.lineTo(s * 0.8f, s * 0.4f);
        phone.quadTo(s * 0.8f, s * 0.8f, s * 0.4f, s * 0.8f);
        phone.lineTo(-s * 0.4f, s * 0.1f);
        phone.close();
        canvas.drawPath(phone, iconPaint);
        iconPaint.setStyle(Paint.Style.STROKE);
        break;

      case TAB_CONTACTS:
        // Іконка "людина" (Person)
        iconPaint.setStyle(selected ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        // Голова
        canvas.drawCircle(0, -s * 0.45f, s * 0.35f, iconPaint);
        // Тіло
        RectF body = new RectF(-s * 0.65f, s * 0.1f, s * 0.65f, s);
        canvas.drawRoundRect(body, s * 0.6f, s * 0.4f, iconPaint);
        iconPaint.setStyle(Paint.Style.STROKE);
        break;

      case TAB_SETTINGS:
        // Іконка "шестеренка" (Gear)
        iconPaint.setStyle(selected ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        // Зовнішнє коло
        canvas.drawCircle(0, 0, s * 0.55f, iconPaint);
        // Внутрішнє коло
        if (selected) {
          // Вирізаємо центральне коло кольором фону
          Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
          holePaint.setColor(colorBackground);
          canvas.drawCircle(0, 0, s * 0.25f, holePaint);
        } else {
          canvas.drawCircle(0, 0, s * 0.25f, iconPaint);
        }
        // Зубчики
        int teeth = 8;
        for (int t = 0; t < teeth; t++) {
          float angle = (float) (t * Math.PI * 2.0 / teeth);
          float x1 = (float) (Math.cos(angle) * s * 0.55f);
          float y1 = (float) (Math.sin(angle) * s * 0.55f);
          float x2 = (float) (Math.cos(angle) * s * 0.8f);
          float y2 = (float) (Math.sin(angle) * s * 0.8f);
          canvas.drawLine(x1, y1, x2, y2, iconPaint);
        }
        iconPaint.setStyle(Paint.Style.STROKE);
        break;
    }

    canvas.restore();
  }

  /** Малює червоний бейдж із числом непрочитаних */
  private void drawBadge(Canvas canvas, int count, float tabCenterX, float iconCenterY) {
    String badgeStr = count > 99 ? "99+" : String.valueOf(count);
    badgePaint.setColor(colorBadge);

    float badgeX = tabCenterX + Screen.dp(10f);
    float badgeY = iconCenterY - Screen.dp(8f);

    // Ширина бейджа залежить від кількості цифр
    float textWidth = badgeTextPaint.measureText(badgeStr);
    float minWidth = Screen.dp(16f);
    float badgeWidth = Math.max(minWidth, textWidth + Screen.dp(8f));
    float badgeHeight = Screen.dp(16f);

    RectF badgeRect = new RectF(
        badgeX - badgeWidth / 2f,
        badgeY - badgeHeight / 2f,
        badgeX + badgeWidth / 2f,
        badgeY + badgeHeight / 2f
    );
    canvas.drawRoundRect(badgeRect, badgeHeight / 2f, badgeHeight / 2f, badgePaint);

    // Текст бейджа з вертикальним центруванням
    Paint.FontMetrics fm = badgeTextPaint.getFontMetrics();
    float textYOffset = badgeY - (fm.top + fm.bottom) / 2f;
    canvas.drawText(badgeStr, badgeX, textYOffset, badgeTextPaint);
  }

  // --- Touch Handling ---

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    final float x = event.getX();

    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN:
        touchedTab = getTabAtX(x);
        return true;

      case MotionEvent.ACTION_UP:
        int clickedTab = getTabAtX(x);
        if (clickedTab >= 0 && clickedTab == touchedTab) {
          // Тактильний відгук
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
          }

          if (clickedTab == selectedTab) {
            if (listener != null) listener.onTabReselected(clickedTab);
          } else {
            previousTab = selectedTab;
            selectedTab = clickedTab;
            animateSelection();
            if (listener != null) listener.onTabSelected(clickedTab);
          }
        }
        touchedTab = -1;
        return true;

      case MotionEvent.ACTION_CANCEL:
        touchedTab = -1;
        return true;
    }
    return true;
  }

  private int getTabAtX(float x) {
    int tab = (int) (x / (getWidth() / (float) TAB_COUNT));
    return Math.max(0, Math.min(tab, TAB_COUNT - 1));
  }

  // --- Color Utilities ---

  /**
   * Лінійна інтерполяція між двома ARGB кольорами.
   */
  private static int blendColor(int from, int to, float fraction) {
    float f = Math.max(0f, Math.min(1f, fraction));
    int fromA = (from >>> 24) & 0xFF;
    int fromR = (from >>> 16) & 0xFF;
    int fromG = (from >>> 8) & 0xFF;
    int fromB = from & 0xFF;
    int toA = (to >>> 24) & 0xFF;
    int toR = (to >>> 16) & 0xFF;
    int toG = (to >>> 8) & 0xFF;
    int toB = to & 0xFF;
    return ((int) (fromA + (toA - fromA) * f) << 24)
        | ((int) (fromR + (toR - fromR) * f) << 16)
        | ((int) (fromG + (toG - fromG) * f) << 8)
        | (int) (fromB + (toB - fromB) * f);
  }
}
