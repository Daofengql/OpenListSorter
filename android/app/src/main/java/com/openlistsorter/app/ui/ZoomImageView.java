package com.openlistsorter.app.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;

import androidx.appcompat.widget.AppCompatImageView;

public final class ZoomImageView extends AppCompatImageView {
    public interface SwipeListener {
        void onSwipeLeft();

        void onSwipeRight();
    }

    private final Matrix imageMatrixValue = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private VelocityTracker velocityTracker;
    private final int minFlingVelocity;
    private SwipeListener swipeListener;
    private float scale = 1f;
    private float fitScale = 1f;
    private float offsetX;
    private float offsetY;

    public ZoomImageView(Context context) {
        this(context, null);
    }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        setBackgroundColor(0xfff8fafc);
        minFlingVelocity = ViewConfiguration.get(context).getScaledMinimumFlingVelocity();
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float old = scale;
                scale = clamp(scale * detector.getScaleFactor(), 0.5f, 8f);
                float ratio = scale / old;
                offsetX = detector.getFocusX() - (detector.getFocusX() - offsetX) * ratio;
                offsetY = detector.getFocusY() - (detector.getFocusY() - offsetY) * ratio;
                applyMatrix();
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent first, MotionEvent current, float distanceX, float distanceY) {
                offsetX -= distanceX;
                offsetY -= distanceY;
                applyMatrix();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                resetView();
                return true;
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getParent() != null && (event.getPointerCount() > 1 || scale > 1f)) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);
        boolean scaled = scaleDetector.onTouchEvent(event);
        boolean gestured = gestureDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP && swipeListener != null && scale <= 1.001f) {
                velocityTracker.computeCurrentVelocity(1000, 8000f);
                float vx = velocityTracker.getXVelocity();
                float vy = velocityTracker.getYVelocity();
                if (Math.abs(vx) > minFlingVelocity && Math.abs(vx) > Math.abs(vy)) {
                    if (vx < 0) swipeListener.onSwipeLeft(); else swipeListener.onSwipeRight();
                }
            }
            velocityTracker.recycle();
            velocityTracker = null;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            if (!scaled) performClick();
        }
        return scaled || gestured || super.onTouchEvent(event);
    }

    public void setSwipeListener(SwipeListener listener) {
        swipeListener = listener;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        resetView();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::resetView);
    }

    public void resetView() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        int width = Math.max(1, drawable.getIntrinsicWidth());
        int height = Math.max(1, drawable.getIntrinsicHeight());
        fitScale = Math.min(getWidth() / (float) width, getHeight() / (float) height);
        scale = 1f;
        offsetX = (getWidth() - width * fitScale) / 2f;
        offsetY = (getHeight() - height * fitScale) / 2f;
        applyMatrix();
    }

    private void applyMatrix() {
        Drawable drawable = getDrawable();
        if (drawable == null) {
            return;
        }
        float actualScale = fitScale * scale;
        float imageWidth = drawable.getIntrinsicWidth() * actualScale;
        float imageHeight = drawable.getIntrinsicHeight() * actualScale;
        if (imageWidth <= getWidth()) {
            offsetX = (getWidth() - imageWidth) / 2f;
        } else {
            offsetX = clamp(offsetX, getWidth() - imageWidth, 0f);
        }
        if (imageHeight <= getHeight()) {
            offsetY = (getHeight() - imageHeight) / 2f;
        } else {
            offsetY = clamp(offsetY, getHeight() - imageHeight, 0f);
        }
        imageMatrixValue.reset();
        imageMatrixValue.postScale(actualScale, actualScale);
        imageMatrixValue.postTranslate(offsetX, offsetY);
        setImageMatrix(imageMatrixValue);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
