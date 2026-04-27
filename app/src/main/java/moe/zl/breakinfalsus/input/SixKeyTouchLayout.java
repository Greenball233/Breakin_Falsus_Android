package moe.zl.breakinfalsus.input;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.card.MaterialCardView;

public class SixKeyTouchLayout extends FrameLayout {

    public interface OnKeyStateChangeListener {
        void onKeyStateChanged(boolean[] keyStates);
    }

    private static final String[] KEY_LABELS = new String[]{"Shift", "A", "S", "D", "F", "Space"};
    private final MaterialCardView[] keyViews = new MaterialCardView[6];
    private final boolean[] keyStates = new boolean[6];
    private OnKeyStateChangeListener listener;
    private final int idleCardColor = 0x661f1e33;
    private final int pressedCardColor = 0xff1f1e33;
    private TextView logt;
    private boolean motionLogEnabled;
    private float chordBufferPx;

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
        setBackgroundColor(0xff000000);
        chordBufferPx = dp(12);
        for (int i = 0; i < keyViews.length; i++) {
            MaterialCardView keyView = new MaterialCardView(context);
            keyView.setCardBackgroundColor(idleCardColor);
            keyView.setRadius(dp(24));
            keyView.setStrokeWidth(dp(2));
            keyView.setStrokeColor(0x881f1e33);
            keyViews[i] = keyView;
            addView(keyView);
        }
        logt = new TextView(context);
        logt.setTextColor(0xffffffff);
        logt.setBackgroundColor(0x66000000);
        logt.setPadding(dp(10), dp(8), dp(10), dp(8));
        logt.setGravity(Gravity.START | Gravity.TOP);
        logt.setTextSize(12f);
        logt.setVisibility(GONE);
        addView(logt);
    }

    public void setOnKeyStateChangeListener(OnKeyStateChangeListener listener) {
        this.listener = listener;
    }

    public void setMotionLogEnabled(boolean enabled) {
        motionLogEnabled = enabled;
        logt.setVisibility(enabled ? VISIBLE : GONE);
        if (!enabled) {
            logt.setText("");
        }
    }

    public void setChordBufferDp(float bufferDp) {
        chordBufferPx = Math.max(0f, bufferDp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        int segmentWidth = width / keyViews.length;
        int margin = dp(2);
        for (int i = 0; i < keyViews.length; i++) {
            int childLeft = i * segmentWidth + margin;
            int childRight = i == keyViews.length - 1 ? width - margin : (i + 1) * segmentWidth - margin;
            keyViews[i].layout(childLeft, margin, childRight, height - margin);
        }
        logt.layout(0, 0, width, height);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean[] nextStates = new boolean[keyStates.length];
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_CANCEL) {
            int liftedPointer = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                    ? event.getActionIndex()
                    : -1;
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (i == liftedPointer) {
                    continue;
                }
                updateStatesForPointer(nextStates, event.getX(i), event.getY(i));
            }
        }
        boolean changed = applyKeyStates(nextStates);
        if (changed && listener != null) {
            listener.onKeyStateChanged(keyStates.clone());
        }
        if (motionLogEnabled) {
            logt.setText(buildMotionLog(event, nextStates));
        }
        return true;
    }

    private boolean applyKeyStates(boolean[] nextStates) {
        boolean changed = false;
        for (int i = 0; i < keyStates.length; i++) {
            if (keyStates[i] != nextStates[i]) {
                keyStates[i] = nextStates[i];
                changed = true;
            }
            keyViews[i].setCardBackgroundColor(keyStates[i] ? pressedCardColor : idleCardColor);
        }
        return changed;
    }

    private void updateStatesForPointer(boolean[] nextStates, float x, float y) {
        if (y < getHeight() / 2f || getWidth() <= 0f) {
            return;
        }
        float segmentWidth = getWidth() / (float) keyStates.length;
        float clampedX = Math.max(0f, Math.min(x, getWidth() - 1f));
        int index = Math.max(0, Math.min(keyStates.length - 1, (int) (clampedX / segmentWidth)));
        nextStates[index] = true;

        float leftEdge = index * segmentWidth;
        float rightEdge = leftEdge + segmentWidth;
        if (clampedX - leftEdge <= chordBufferPx && index > 0) {
            nextStates[index - 1] = true;
        }
        if (rightEdge - clampedX <= chordBufferPx && index < keyStates.length - 1) {
            nextStates[index + 1] = true;
        }
    }

    private String buildMotionLog(MotionEvent event, boolean[] nextStates) {
        StringBuilder builder = new StringBuilder();
        builder.append(actionToString(event.getActionMasked()))
                .append(" idx=")
                .append(event.getActionIndex())
                .append(" ptr=")
                .append(event.getPointerCount())
                .append('\n');
        for (int i = 0; i < event.getPointerCount(); i++) {
            builder.append('#')
                    .append(event.getPointerId(i))
                    .append(" (")
                    .append(Math.round(event.getX(i)))
                    .append(", ")
                    .append(Math.round(event.getY(i)))
                    .append(')');
            if (i == event.getActionIndex()) {
                builder.append(" *");
            }
            builder.append('\n');
        }
        builder.append("keys=");
        for (boolean state : nextStates) {
            builder.append(state ? '1' : '0');
        }
        builder.append(" buffer=")
                .append(Math.round(chordBufferPx / getResources().getDisplayMetrics().density))
                .append("dp");
        return builder.toString();
    }

    private String actionToString(int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                return "DOWN";
            case MotionEvent.ACTION_UP:
                return "UP";
            case MotionEvent.ACTION_MOVE:
                return "MOVE";
            case MotionEvent.ACTION_CANCEL:
                return "CANCEL";
            case MotionEvent.ACTION_POINTER_DOWN:
                return "POINTER_DOWN";
            case MotionEvent.ACTION_POINTER_UP:
                return "POINTER_UP";
            default:
                return "ACTION_" + action;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
