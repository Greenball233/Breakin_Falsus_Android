package moe.zl.breakinfalsus.input;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import moe.zl.breakinfalsus.R;

public class SixKeyTouchLayout extends FrameLayout {

    public interface OnKeyStateChangeListener {
        void onKeyStateChanged(boolean[] keyStates);
    }

    private static final String[] KEY_LABELS = new String[]{"Shift", "A", "S", "D", "F", "Space"};
    private final View[] keyViews = new View[6];
    private final boolean[] keyStates = new boolean[6];
    private OnKeyStateChangeListener listener;

    public SixKeyTouchLayout(@NonNull Context context) {
        super(context);
        init(context);
    }

    public SixKeyTouchLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SixKeyTouchLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setClickable(true);
        setFocusable(true);
        setBackgroundColor(Color.parseColor("#FF10131A"));
        for (int i = 0; i < keyViews.length; i++) {
            TextView keyView = new TextView(context);
            keyView.setText(KEY_LABELS[i]);
            keyView.setGravity(Gravity.CENTER);
            keyView.setTextColor(Color.WHITE);
            keyView.setTextSize(18f);
            keyView.setBackground(AppCompatResources.getDrawable(context, R.drawable.key_idle));
            keyViews[i] = keyView;
            addView(keyView);
        }
    }

    public void setOnKeyStateChangeListener(OnKeyStateChangeListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        int segmentWidth = width / keyViews.length;
        int margin = dp(8);
        for (int i = 0; i < keyViews.length; i++) {
            int childLeft = i * segmentWidth + margin;
            int childRight = i == keyViews.length - 1 ? width - margin : (i + 1) * segmentWidth - margin;
            keyViews[i].layout(childLeft, height / 2, childRight, height - margin);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean[] nextStates = new boolean[keyStates.length];
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                float x = event.getX(i);
                float y = event.getY(i);
                if (y < getHeight() / 2f) {
                    continue;
                }
                int index = (int) (x / Math.max(1, getWidth() / (float) keyStates.length));
                if (index >= 0 && index < nextStates.length) {
                    nextStates[index] = true;
                }
            }
        }
        boolean changed = false;
        for (int i = 0; i < keyStates.length; i++) {
            if (keyStates[i] != nextStates[i]) {
                keyStates[i] = nextStates[i];
                changed = true;
            }
            keyViews[i].setBackground(AppCompatResources.getDrawable(
                    getContext(),
                    keyStates[i] ? R.drawable.key_pressed : R.drawable.key_idle
            ));
        }
        if (changed && listener != null) {
            listener.onKeyStateChanged(keyStates.clone());
        }
        return true;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
