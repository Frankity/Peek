/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jedga.peek.free.helpers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.graphics.RectF;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.LinearInterpolator;

public class SwipeHelper implements Gefingerpoken {
    static final String TAG = "com.android.systemui.SwipeHelper";

    private static final boolean DEBUG = false;
    private static final boolean DEBUG_INVALIDATE = false;
    private static final boolean SLOW_ANIMATIONS = false; // DEBUG;

    public static final float TOP_DISTANCE_CONSTRAINT_DISABLED = 0.7f;
    public static final int GESTURE_POSITIVE = 0;
    public static final int GESTURE_NEGATIVE = 1;
    public static final int X = 0;
    public static final int Y = 1;

    private static LinearInterpolator sLinearInterpolator = new LinearInterpolator();
    private float SWIPE_ESCAPE_VELOCITY = 100f; // dp/sec
    private int DEFAULT_ESCAPE_ANIMATION_DURATION = 200; // ms
    private int MAX_ESCAPE_ANIMATION_DURATION = 400; // ms
    private int MAX_DISMISS_VELOCITY = 2000; // dp/sec
    private static final int SNAP_ANIM_LEN = SLOW_ANIMATIONS ? 1000 : 150; // ms

    public static float ALPHA_FADE_START = 0f; // fraction of thumbnail width
    // where fade starts
    static final float ALPHA_FADE_END = 0.5f; // fraction of thumbnail width
    // beyond which alpha->0
    private float mMinAlpha = 0f;

    private float mPagingTouchSlop;
    private Callback mCallback;
    private Handler mHandler;
    private int mSwipeDirection;
    private VelocityTracker mVelocityTracker;

    private float mInitialTouchPos;
    private boolean mDragging;
    private View mCurrView;
    private View mCurrAnimView;
    private boolean mCanCurrViewBeDimissed;
    private boolean mAllowDragAfterLongPress;
    private int mCurrGestureDirection;
    private float mDensityScale;

    private boolean mLongPressSent;
    private View.OnLongClickListener mLongPressListener;
    private Runnable mWatchLongPress;
    private long mLongPressTimeout;

    public SwipeHelper(int swipeDirection, Callback callback, float densityScale,
                       float pagingTouchSlop) {
        mCallback = callback;
        mHandler = new Handler();
        mSwipeDirection = swipeDirection;
        mVelocityTracker = VelocityTracker.obtain();
        mDensityScale = densityScale;
        mPagingTouchSlop = pagingTouchSlop;

        mLongPressTimeout = (long) (ViewConfiguration.getLongPressTimeout() * 1.5f); // extra long-press!
    }

    public void setLongPressListener(View.OnLongClickListener listener) {
        mLongPressListener = listener;
    }

    public void setDensityScale(float densityScale) {
        mDensityScale = densityScale;
    }

    public void setPagingTouchSlop(float pagingTouchSlop) {
        mPagingTouchSlop = pagingTouchSlop;
    }

    public void setAllowDragAfterLongPress(boolean allowDrag) {
        mAllowDragAfterLongPress = allowDrag;
    }

    public void setDragging(boolean dragging, MotionEvent ev) {
        if (mDragging != dragging) {
            mDragging = dragging;
            if (dragging) {
                mCallback.onBeginDrag(mCurrView);
                mInitialTouchPos = getPos(ev) - getTranslation(mCurrAnimView);
            }
        }
        removeLongPressCallback();
    }

    private float getPos(MotionEvent ev) {
        return mSwipeDirection == X ? ev.getX() : ev.getY();
    }

    private float getTranslation(View v) {
        return mSwipeDirection == X ? v.getTranslationX() : v.getTranslationY();
    }

    private float getVelocity(VelocityTracker vt) {
        return mSwipeDirection == X ? vt.getXVelocity() :
                vt.getYVelocity();
    }

    private ObjectAnimator createTranslationAnimation(View v, float newPos) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(v,
                mSwipeDirection == X ? "translationX" : "translationY", newPos);
        return anim;
    }

    private float getPerpendicularVelocity(VelocityTracker vt) {
        return mSwipeDirection == X ? vt.getYVelocity() :
                vt.getXVelocity();
    }

    private void setTranslation(View v, float translate) {
        if (mSwipeDirection == X) {
            v.setTranslationX(translate);
        } else {
            v.setTranslationY(translate);
        }
    }

    private float getSize(View v) {
        return mSwipeDirection == X ? v.getMeasuredWidth() :
                v.getMeasuredHeight();
    }

    public void setMinAlpha(float minAlpha) {
        mMinAlpha = minAlpha;
    }

    private float getAlphaForOffset(View view) {
        float viewSize = getSize(view);
        final float fadeSize = ALPHA_FADE_END * viewSize;
        float result = 1.0f;
        float pos = getTranslation(view);
        if (pos >= viewSize * ALPHA_FADE_START) {
            result = 1.0f - (pos - viewSize * ALPHA_FADE_START) / fadeSize;
        } else if (pos < viewSize * (1.0f - ALPHA_FADE_START)) {
            result = 1.0f + (viewSize * ALPHA_FADE_START + pos) / fadeSize;
        }
        return Math.max(mMinAlpha, result);
    }

    private void updateAlphaFromOffset(View animView, boolean dismissable) {
        float alpha = getAlphaForOffset(animView);
        if (mCallback.isFadeoutEnabled(mCurrGestureDirection) && dismissable) {
            if (alpha != 0f && alpha != 1f) {
                animView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            } else {
                animView.setLayerType(View.LAYER_TYPE_NONE, null);
            }
            animView.setAlpha(alpha);
        }
        //if (mCallback.canChildBeDismissed(mCurrGestureDirection, animView)) {
            mCallback.onDragUpdate(mCurrGestureDirection, alpha);
        //}
        invalidateGlobalRegion(animView);
    }

    // invalidate the view's own bounds all the way up the view hierarchy
    public static void invalidateGlobalRegion(View view) {
        invalidateGlobalRegion(
                view,
                new RectF(view.getLeft(), view.getTop(), view.getRight(), view.getBottom()));
    }

    // invalidate a rectangle relative to the view's coordinate system all the way up the view
    // hierarchy
    public static void invalidateGlobalRegion(View view, RectF childBounds) {
        if (DEBUG_INVALIDATE)
            Log.v(TAG, "-------------");
        while (view.getParent() != null && view.getParent() instanceof View) {
            view = (View) view.getParent();
            view.getMatrix().mapRect(childBounds);
            view.invalidate((int) Math.floor(childBounds.left),
                    (int) Math.floor(childBounds.top),
                    (int) Math.ceil(childBounds.right),
                    (int) Math.ceil(childBounds.bottom));
            if (DEBUG_INVALIDATE) {
                Log.v(TAG, "INVALIDATE(" + (int) Math.floor(childBounds.left)
                        + "," + (int) Math.floor(childBounds.top)
                        + "," + (int) Math.ceil(childBounds.right)
                        + "," + (int) Math.ceil(childBounds.bottom));
            }
        }
    }

    public void removeLongPressCallback() {
        if (mWatchLongPress != null) {
            mHandler.removeCallbacks(mWatchLongPress);
            mWatchLongPress = null;
        }
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                setDragging(false, null);
                mLongPressSent = false;
                mCurrView = mCallback.getChildAtPosition(ev);
                mVelocityTracker.clear();
                if (mCurrView != null) {
                    mCurrAnimView = mCallback.getChildContentView(mCurrView);
                    mCanCurrViewBeDimissed = mCallback
                            .canChildBeDismissed(mCurrGestureDirection, mCurrView);
                    mVelocityTracker.addMovement(ev);
                    mInitialTouchPos = getPos(ev);

                    if (mLongPressListener != null) {
                        if (mWatchLongPress == null) {
                            mWatchLongPress = new Runnable() {
                                @Override
                                public void run() {
                                    if (mCurrView != null && !mLongPressSent) {
                                        mLongPressSent = true;
                                        mCurrView
                                                .sendAccessibilityEvent(
                                                        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                                        mLongPressListener.onLongClick(mCurrView);
                                    }
                                }
                            };
                        }
                        mHandler.postDelayed(mWatchLongPress, mLongPressTimeout);
                    }
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (mCurrView != null && (!mLongPressSent || mAllowDragAfterLongPress)) {
                    mVelocityTracker.addMovement(ev);
                    float pos = getPos(ev);
                    float delta = pos - mInitialTouchPos;
                    if (Math.abs(delta) > mPagingTouchSlop) {
                        setDragging(true, ev);
                        mLongPressSent = false;
                    }
                }

                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                mCurrView = null;
                mCurrAnimView = null;
                removeLongPressCallback();
                if (mLongPressSent) {
                    mLongPressSent = false;
                    return true;
                }
        }
        return mDragging;
    }

    /**
     * @param view     The view to be dismissed
     * @param velocity The desired pixels/second speed at which the view should move
     */
    public void dismissChild(final View view, float velocity) {
        final View animView = mCallback.getChildContentView(view);
        final boolean canAnimViewBeDismissed = mCallback
                .canChildBeDismissed(mCurrGestureDirection, view);
        float newPos;

        if (velocity < 0
                || (velocity == 0 && getTranslation(animView) < 0)
                // if we use the Menu to dismiss an item in landscape, animate up
                || (velocity == 0 && getTranslation(animView) == 0 && mSwipeDirection == Y)) {
            newPos = -getSize(animView);
        } else {
            newPos = getSize(animView);
        }
        int duration = MAX_ESCAPE_ANIMATION_DURATION;
        if (velocity != 0) {
            duration = Math.min(duration,
                    (int) (Math.abs(newPos - getTranslation(animView)) * 1000f / Math
                            .abs(velocity))
            );
        } else {
            duration = DEFAULT_ESCAPE_ANIMATION_DURATION;
        }

        animView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        ObjectAnimator anim = createTranslationAnimation(animView, newPos);
        anim.setInterpolator(sLinearInterpolator);
        anim.setDuration(duration);
        anim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
                mCallback.onChildDismissed(mCurrGestureDirection, view);
                animView.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        });
        anim.addUpdateListener(new AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                updateAlphaFromOffset(animView, canAnimViewBeDismissed);
            }
        });
        anim.start();
    }

    public void snapChild() {
        snapChild(mCurrView, 0, true);
    }

    public void snapChild(final View view, float velocity, final boolean dragCancelled) {
        final View animView = mCallback.getChildContentView(view);
        final boolean canAnimViewBeDismissed = mCallback
                .canChildBeDismissed(mCurrGestureDirection, animView);
        ObjectAnimator anim = createTranslationAnimation(animView, 0);
        int duration = SNAP_ANIM_LEN;
        anim.setDuration(duration);
        anim.addUpdateListener(new AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                updateAlphaFromOffset(animView, canAnimViewBeDismissed);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animator) {
                updateAlphaFromOffset(animView, canAnimViewBeDismissed);
                if (dragCancelled) mCallback.onDragCancelled(mCurrView);
            }
        });
        anim.start();
    }

    public boolean onTouchEvent(MotionEvent ev) {
        if (mLongPressSent && !mAllowDragAfterLongPress) {
            return true;
        }

        if (!mDragging) {
            // We are not doing anything, make sure the long press callback
            // is not still ticking like a bomb waiting to go off.
            removeLongPressCallback();
            return false;
        }

        mVelocityTracker.addMovement(ev);
        final int action = ev.getAction();
        switch (action) {
            case MotionEvent.ACTION_OUTSIDE:
            case MotionEvent.ACTION_MOVE:
                if (mCurrView != null) {
                    float delta = getPos(ev) - mInitialTouchPos;
                    // store current direction of the gesture
                    mCurrGestureDirection = delta <= 0 ? GESTURE_NEGATIVE : GESTURE_POSITIVE;
                    // don't let items that can't be dismissed be dragged more than
                    // maxScrollDistance.
                    if (!mCallback.canChildBeDismissed(mCurrGestureDirection, mCurrView)) {
                        float size = getSize(mCurrAnimView);
                        float maxScrollDistance
                                = (mCallback.isConstrainSwipeEnabled() ? 0.15f : 0f) * size;
                        if (Math.abs(delta) >= size) {
                            delta = delta > 0 ? maxScrollDistance : -maxScrollDistance;
                        } else {
                            delta = maxScrollDistance * (float) Math.sin((delta / size) * (Math.PI / 2));
                        }
                        // Recalculate gesture direction because delta has changed
                        mCurrGestureDirection = delta <= 0 ? GESTURE_NEGATIVE : GESTURE_POSITIVE;
                    }
                    setTranslation(mCurrAnimView, delta);

                    updateAlphaFromOffset(mCurrAnimView, mCanCurrViewBeDimissed);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mCurrView != null) {
                    float maxVelocity = MAX_DISMISS_VELOCITY * mDensityScale;
                    mVelocityTracker.computeCurrentVelocity(1000 /* px/sec */, maxVelocity);
                    float escapeVelocity = SWIPE_ESCAPE_VELOCITY * mDensityScale;
                    float velocity = getVelocity(mVelocityTracker);
                    float perpendicularVelocity = getPerpendicularVelocity(mVelocityTracker);
                    float translation = getTranslation(mCurrAnimView);
                    float size = getSize(mCurrAnimView);

                    // Decide whether to dismiss the current view
                    boolean childSwipedFarEnough = ((translation < -0.4 * size && velocity >= 0 ||
                            (translation < -0.1 * size && velocity < 0) ||
                            (translation > 0.1 * size && velocity > 0) ||
                            (translation > 0.4 * size && velocity < 0)));
                    boolean childSwipedFastEnough = (Math.abs(velocity) > escapeVelocity) &&
                            (Math.abs(velocity) > Math.abs(perpendicularVelocity)) &&
                            (velocity > 0) == (translation > 0);

                    boolean dismissChild = mCallback.canChildBeDismissed(mCurrGestureDirection,
                            mCurrView) && (childSwipedFastEnough || childSwipedFarEnough);

                    if (dismissChild) {
                        // flingadingy
                        dismissChild(mCurrView, childSwipedFastEnough ? velocity : 0f);
                    } else {
                        // snappity
                        snapChild(mCurrView, velocity, true);
                    }
                }
                break;
        }
        return true;
    }

    public interface Callback {
        View getChildAtPosition(MotionEvent ev);

        View getChildContentView(View v);

        boolean isConstrainSwipeEnabled();

        boolean isFadeoutEnabled(int gestureDirection);

        boolean canChildBeDismissed(int gestureDirection, View v);

        void onChildDismissed(int gestureDirection, View v);

        void onBeginDrag(View v);

        void onDragUpdate(int gestureDirection, float progress);

        void onDragCancelled(View v);
    }
}