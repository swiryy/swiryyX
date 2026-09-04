/*
 * Telegram X - Nicegram Edition
 * Bottom Navigation Layout (Нижня навігаційна панель у стилі iOS / Nicegram)
 */

package org.thunderdog.challegram.navigation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.tool.Screen;

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
  private final int[] unreadCounts = new int[TAB_COUNT];
  private final String[] tabTitles = new String[]{"Чати", "Дзвінки", "Контакти", "Налаштування"};

  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint separatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint backgroundPaint = new Paint();

  private int colorActive = 0xFF6C5CE7;    // Nicegram Purple / iOS Blue
  private int colorInactive = 0xFF8E8E93;  // iOS Neutral Gray
  private int colorBackground = 0xFF1C1C1E;// iOS Dark Bar
  private int colorSeparator = 0xFF2C2C2E; // iOS Dark Separator
  private int colorBadge = 0xFFFF3B30;     // iOS Red Badge

  private OnTabSelectedListener listener;

  public BottomNavigationLayout(Context context) {
    super(context);
    init();
  }

  private void init() {
    textPaint.setTextSize(Screen.dp(11f));
    textPaint.setTextAlign(Paint.Align.CENTER);

    badgeTextPaint.setTextSize(Screen.dp(10f));
    badgeTextPaint.setTextAlign(Paint.Align.CENTER);
    badgeTextPaint.setColor(0xFFFFFFFF);
    badgeTextPaint.setFakeBoldText(true);

    separatorPaint.setStrokeWidth(Screen.dp(0.5f));
  }

  public void setOnTabSelectedListener(OnTabSelectedListener listener) {
    this.listener = listener;
  }

  public void setSelectedTab(int tabIndex) {
    if (tabIndex >= 0 && tabIndex < TAB_COUNT && selectedTab != tabIndex) {
      selectedTab = tabIndex;
      invalidate();
    }
  }

  public int getSelectedTab() {
    return selectedTab;
  }

  public void setBadgeCount(int tabIndex, int count) {
    if (tabIndex >= 0 && tabIndex < TAB_COUNT && unreadCounts[tabIndex] != count) {
      unreadCounts[tabIndex] = count;
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

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int height = Screen.dp(52f); // Висота панелі у стилі iOS
    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);

    final int width = getWidth();
    final int height = getHeight();
    if (width == 0 || height == 0) return;

    // 1. Фон
    backgroundPaint.setColor(colorBackground);
    canvas.drawRect(0, 0, width, height, backgroundPaint);

    // 2. Верхній делікатний розділювач (separator)
    separatorPaint.setColor(colorSeparator);
    canvas.drawLine(0, 0, width, 0, separatorPaint);

    // 3. Відмальовка табів
    final float tabWidth = (float) width / TAB_COUNT;
    final float centerY = height * 0.42f;

    for (int i = 0; i < TAB_COUNT; i++) {
      final float tabCenterX = tabWidth * i + (tabWidth / 2f);
      final boolean isSelected = (i == selectedTab);
      final int itemColor = isSelected ? colorActive : colorInactive;

      // Текст вкладки
      textPaint.setColor(itemColor);
      textPaint.setFakeBoldText(isSelected);
      canvas.drawText(tabTitles[i], tabCenterX, height - Screen.dp(7f), textPaint);

      // Бейдж непрочитаних повідомлень
      if (unreadCounts[i] > 0) {
        String badgeStr = unreadCounts[i] > 99 ? "99+" : String.valueOf(unreadCounts[i]);
        badgePaint.setColor(colorBadge);

        float badgeX = tabCenterX + Screen.dp(12f);
        float badgeY = Screen.dp(9f);
        float badgeRadius = Screen.dp(8f);

        canvas.drawCircle(badgeX, badgeY, badgeRadius, badgePaint);
        canvas.drawText(badgeStr, badgeX, badgeY + Screen.dp(3.5f), badgeTextPaint);
      }
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (event.getAction() == MotionEvent.ACTION_UP) {
      float x = event.getX();
      int clickedTab = (int) (x / (getWidth() / (float) TAB_COUNT));
      if (clickedTab >= 0 && clickedTab < TAB_COUNT) {
        if (clickedTab == selectedTab) {
          if (listener != null) listener.onTabReselected(clickedTab);
        } else {
          selectedTab = clickedTab;
          invalidate();
          if (listener != null) listener.onTabSelected(clickedTab);
        }
        return true;
      }
    }
    return true;
  }
}
