/*
 * Copyright (C) 2019 The Android Open Source Project
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

package androidx.camera.view;

import android.Manifest.permission;
import android.annotation.TargetApi;
import androidx.lifecycle.LifecycleOwner;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.annotation.UiThread;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.BaseInterpolator;
import android.view.animation.DecelerateInterpolator;
import androidx.camera.core.CameraX.LensFacing;
import androidx.camera.core.FlashMode;
import androidx.camera.core.ImageCaptureUseCase.OnImageCapturedListener;
import androidx.camera.core.ImageCaptureUseCase.OnImageSavedListener;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.VideoCaptureUseCase.OnVideoSavedListener;
import java.io.File;

/**
 * A {@link View} that displays a preview of the camera with methods {@link
 * #takePicture(OnImageCapturedListener)}, {@link #takePicture(File, OnImageSavedListener)}, {@link
 * #startRecording(File, OnVideoSavedListener)} and {@link #stopRecording()}.
 *
 * <p>Because the Camera is a limited resource and consumes a high amount of power, CameraView must
 * be opened/closed. CameraView will handle opening/closing automatically through use of a {@link
 * LifecycleOwner}. Use {@link #bindToLifecycle(LifecycleOwner)} to start the camera.
 */
public final class CameraView extends ViewGroup {
  static final String TAG = androidx.camera.view.CameraView.class.getSimpleName();
  static final boolean DEBUG = false;

  static final int INDEFINITE_VIDEO_DURATION = -1;
  static final int INDEFINITE_VIDEO_SIZE = -1;

  private static final String EXTRA_SUPER = "super";
  private static final String EXTRA_QUALITY = "quality";
  private static final String EXTRA_ZOOM_LEVEL = "zoom_level";
  private static final String EXTRA_PINCH_TO_ZOOM_ENABLED = "pinch_to_zoom_enabled";
  private static final String EXTRA_FLASH = "flash";
  private static final String EXTRA_MAX_VIDEO_DURATION = "max_video_duration";
  private static final String EXTRA_MAX_VIDEO_SIZE = "max_video_size";
  private static final String EXTRA_SCALE_TYPE = "scale_type";
  private static final String EXTRA_CAMERA_DIRECTION = "camera_direction";
  private static final String EXTRA_CAPTURE_MODE = "captureMode";

  private static final int LENS_FACING_NONE = 0;
  private static final int LENS_FACING_FRONT = 1;
  private static final int LENS_FACING_BACK = 2;
  private static final int FLASH_MODE_AUTO = 1;
  private static final int FLASH_MODE_ON = 2;
  private static final int FLASH_MODE_OFF = 4;

  /** Options for scaling the bounds of the view finder to the bounds of this view. */
  public enum ScaleType {
    /**
     * Scale the view finder, maintaining the source aspect ratio, so the view finder fills the
     * entire view. This will cause the view finder to crop the source image if the camera aspect
     * ratio does not match the view aspect ratio.
     */
    CENTER_CROP(0),
    /**
     * Scale the view finder, maintaining the source aspect ratio, so the view finder is entirely
     * contained within the view.
     */
    CENTER_INSIDE(1);

    private final int id;

    ScaleType(int id) {
      this.id = id;
    }

    static ScaleType fromId(int id) {
      for (ScaleType st : values()) {
        if (st.id == id) {
          return st;
        }
      }
      throw new IllegalArgumentException();
    }
  }

  /**
   * Determines the resolution of CameraView's outputs. All resolutions are best attempts, and will
   * fall to lower qualities if the Android device cannot support them. Resolutions also may change
   * in the future (if, say, Android adds 8k resolution).
   *
   * <p>(*) {@link Quality#MAX} will output at 4k. (*) {@link Quality#HIGH} will output at 1080p.
   * (*) {@link Quality#MEDIUM} will output at 720. (*) {@link Quality#LOW} will output at 480.
   *
   * @hide Not currently connected to use cases.
   */
  public enum Quality {
    MAX(0),
    HIGH(1),
    MEDIUM(2),
    LOW(3);

    private final int id;

    Quality(int id) {
      this.id = id;
    }

    static Quality fromId(int id) {
      for (Quality f : values()) {
        if (f.id == id) {
          return f;
        }
      }
      throw new IllegalArgumentException();
    }
  }

  /**
   * The capture mode used by CameraView.
   *
   * <p>This enum can be used to determine which capture mode will be enabled for {@link
   * CameraView}.
   */
  public enum CaptureMode {
    /** A mode where image capture is enabled. */
    IMAGE(0),
    /** A mode where video capture is enabled. */
    VIDEO(1),
    /**
     * A mode where both image capture and video capture are simultaneously enabled. Note that this
     * mode may not be available on every device.
     */
    MIXED(2);

    private final int id;

    CaptureMode(int id) {
      this.id = id;
    }

    static CaptureMode fromId(int id) {
      for (CaptureMode f : values()) {
        if (f.id == id) {
          return f;
        }
      }
      throw new IllegalArgumentException();
    }
  }

  // For tap-to-focus
  private long downEventTimestamp;
  private final Rect focusingRect = new Rect();
  private final Rect meteringRect = new Rect();

  // For pinch-to-zoom
  private PinchToZoomGestureDetector pinchToZoomGestureDetector;
  private boolean isPinchToZoomEnabled = true;

  private CameraXModule cameraModule;

  private TextureView cameraTextureView;
  private Size viewFinderSrcSize = new Size(0, 0);
  private ScaleType scaleType = ScaleType.CENTER_CROP;

  // For accessibility event
  private MotionEvent upEvent;

  private @Nullable Paint layerPaint;

  private final DisplayManager.DisplayListener displayListener =
      new DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
          cameraModule.invalidateView();
        }
      };

  public CameraView(Context context) {
    this(context, null);
  }

  public CameraView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public CameraView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    init(context, attrs);
  }

  @TargetApi(21)
  public CameraView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    init(context, attrs);
  }

  /**
   * Binds control of the camera used by this view to the given lifecycle.
   *
   * <p>This links opening/closing the camera to the given lifecycle. The camera will not operate
   * unless this method is called with a valid {@link LifecycleOwner} that is not in the {@link
   * android.arch.lifecycle.Lifecycle.State#DESTROYED} state. Call this method only once camera
   * permissions have been obtained.
   *
   * <p>Once the provided lifecycle has transitioned to a {@link
   * android.arch.lifecycle.Lifecycle.State#DESTROYED} state, CameraView must be bound to a new
   * lifecycle through this method in order to operate the camera.
   *
   * @param lifecycleOwner The lifecycle that will control this view's camera
   * @throws IllegalArgumentException if provided lifecycle is in a {@link
   *     android.arch.lifecycle.Lifecycle.State#DESTROYED} state.
   * @throws IllegalStateException if camera permissions are not granted.
   */
  @RequiresPermission(permission.CAMERA)
  public void bindToLifecycle(LifecycleOwner lifecycleOwner) {
    cameraModule.bindToLifecycle(lifecycleOwner);
  }

  private void init(Context context, @Nullable AttributeSet attrs) {
    addView(cameraTextureView = new TextureView(getContext()), 0 /* view position */);
    cameraTextureView.setLayerPaint(layerPaint);
    cameraModule = new CameraXModule(this);

    if (isInEditMode()) {
      onViewfinderSourceDimensUpdated(640, 480);
    }

    if (attrs != null) {
      TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CameraView);
      setScaleType(
          ScaleType.fromId(a.getInteger(R.styleable.CameraView_scaleType, getScaleType().id)));
      setQuality(Quality.fromId(a.getInteger(R.styleable.CameraView_quality, getQuality().id)));
      setPinchToZoomEnabled(
          a.getBoolean(R.styleable.CameraView_pinchToZoomEnabled, isPinchToZoomEnabled()));
      setCaptureMode(
          CaptureMode.fromId(
              a.getInteger(R.styleable.CameraView_captureMode, getCaptureMode().id)));

      int lensFacing = a.getInt(R.styleable.CameraView_lensFacing, LENS_FACING_BACK);
      switch(lensFacing) {
        case LENS_FACING_NONE:
          setCameraByLensFacing(null);
          break;
        case LENS_FACING_FRONT:
          setCameraByLensFacing(LensFacing.FRONT);
          break;
        case LENS_FACING_BACK:
          setCameraByLensFacing(LensFacing.BACK);
          break;
        default:
          // Unhandled event.
      }

      int flashMode = a.getInt(R.styleable.CameraView_flash, 0);
      switch (flashMode) {
        case FLASH_MODE_AUTO:
          setFlash(FlashMode.AUTO);
          break;
        case FLASH_MODE_ON:
          setFlash(FlashMode.ON);
          break;
        case FLASH_MODE_OFF:
          setFlash(FlashMode.OFF);
          break;
        default:
          // Unhandled event.
      }

      a.recycle();
    }

    if (getBackground() == null) {
      setBackgroundColor(0xFF111111);
    }

    pinchToZoomGestureDetector = new PinchToZoomGestureDetector(context);
  }

  @Override
  protected LayoutParams generateDefaultLayoutParams() {
    return new LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
  }

  @Override
  protected Parcelable onSaveInstanceState() {
    // TODO(b/113884082): Decide what belongs here or what should be invalidated on configuration
    // change
    Bundle state = new Bundle();
    state.putParcelable(EXTRA_SUPER, super.onSaveInstanceState());
    state.putInt(EXTRA_SCALE_TYPE, getScaleType().id);
    state.putInt(EXTRA_QUALITY, getQuality().id);
    state.putFloat(EXTRA_ZOOM_LEVEL, getZoomLevel());
    state.putBoolean(EXTRA_PINCH_TO_ZOOM_ENABLED, isPinchToZoomEnabled());
    state.putString(EXTRA_FLASH, getFlash().name());
    state.putLong(EXTRA_MAX_VIDEO_DURATION, getMaxVideoDuration());
    state.putLong(EXTRA_MAX_VIDEO_SIZE, getMaxVideoSize());
    if (getCameraLensFacing() != null) {
      state.putString(EXTRA_CAMERA_DIRECTION, getCameraLensFacing().name());
    }
    state.putInt(EXTRA_CAPTURE_MODE, getCaptureMode().id);
    return state;
  }

  @Override
  protected void onRestoreInstanceState(Parcelable savedState) {
    // TODO(b/113884082): Decide what belongs here or what should be invalidated on configuration
    // change
    if (savedState instanceof Bundle) {
      Bundle state = (Bundle) savedState;
      super.onRestoreInstanceState(state.getParcelable(EXTRA_SUPER));
      setScaleType(ScaleType.fromId(state.getInt(EXTRA_SCALE_TYPE)));
      setQuality(Quality.fromId(state.getInt(EXTRA_QUALITY)));
      setZoomLevel(state.getFloat(EXTRA_ZOOM_LEVEL));
      setPinchToZoomEnabled(state.getBoolean(EXTRA_PINCH_TO_ZOOM_ENABLED));
      setFlash(FlashMode.valueOf(state.getString(EXTRA_FLASH)));
      setMaxVideoDuration(state.getLong(EXTRA_MAX_VIDEO_DURATION));
      setMaxVideoSize(state.getLong(EXTRA_MAX_VIDEO_SIZE));
      String lensFacingString = state.getString(EXTRA_CAMERA_DIRECTION);
      setCameraByLensFacing(
          TextUtils.isEmpty(lensFacingString) ? null : LensFacing.valueOf(lensFacingString));
      setCaptureMode(CaptureMode.fromId(state.getInt(EXTRA_CAPTURE_MODE)));
    } else {
      super.onRestoreInstanceState(savedState);
    }
  }

  /**
   * Sets the paint on the viewfinder.
   *
   * <p>This only affects the viewfinder, and does not affect captured images/video.
   *
   * @param paint The paint object to apply to the viewfinder.
   * @hide This may not work once {@link android.view.SurfaceView} is supported along with {@link
   *     TextureView}.
   */
  @Override
  public void setLayerPaint(@Nullable Paint paint) {
    super.setLayerPaint(paint);
    layerPaint = paint;
    cameraTextureView.setLayerPaint(paint);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    DisplayManager dpyMgr = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
    dpyMgr.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    DisplayManager dpyMgr = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
    dpyMgr.unregisterDisplayListener(displayListener);
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int viewWidth = MeasureSpec.getSize(widthMeasureSpec);
    int viewHeight = MeasureSpec.getSize(heightMeasureSpec);

    int displayRotation = getDisplay().getRotation();

    if (viewFinderSrcSize.getHeight() == 0 || viewFinderSrcSize.getWidth() == 0) {
      super.onMeasure(widthMeasureSpec, heightMeasureSpec);
      cameraTextureView.measure(viewWidth, viewHeight);
    } else {
      Size scaled =
          calculateViewfinderViewDimens(
              viewFinderSrcSize, viewWidth, viewHeight, displayRotation, scaleType);
      super.setMeasuredDimension(
          Math.min(scaled.getWidth(), viewWidth), Math.min(scaled.getHeight(), viewHeight));
      cameraTextureView.measure(scaled.getWidth(), scaled.getHeight());
    }

    // Since bindToLifecycle will depend on the measured dimension, only call it when measured
    // dimension is not 0x0
    if (getMeasuredWidth() > 0 && getMeasuredHeight() > 0) {
      cameraModule.bindToLifecycleAfterViewMeasured();
    }
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    // In case that the CameraView size is always set as 0x0, we still need to trigger to force
    // binding to lifecycle
    cameraModule.bindToLifecycleAfterViewMeasured();

    // If we don't know the src buffer size yet, set the viewfinder to be the parent size
    if (viewFinderSrcSize.getWidth() == 0 || viewFinderSrcSize.getHeight() == 0) {
      cameraTextureView.layout(left, top, right, bottom);
      return;
    }

    // Compute the viewfinder ui size based on the available width, height, and ui orientation.
    int viewWidth = (right - left);
    int viewHeight = (bottom - top);
    int displayRotation = getDisplay().getRotation();
    Size scaled =
        calculateViewfinderViewDimens(
            viewFinderSrcSize, viewWidth, viewHeight, displayRotation, scaleType);

    // Compute the center of the view.
    int centerX = viewWidth / 2;
    int centerY = viewHeight / 2;

    // Compute the left / top / right / bottom values such that viewfinder is centered.
    int layoutL = centerX - (scaled.getWidth() / 2);
    int layoutT = centerY - (scaled.getHeight() / 2);
    int layoutR = layoutL + scaled.getWidth();
    int layoutB = layoutT + scaled.getHeight();

    // Layout debugging
    log("layout: viewWidth:  " + viewWidth);
    log("layout: viewHeight: " + viewHeight);
    log("layout: viewRatio:  " + (viewWidth / (float) viewHeight));
    log("layout: sizeWidth:  " + viewFinderSrcSize.getWidth());
    log("layout: sizeHeight: " + viewFinderSrcSize.getHeight());
    log(
        "layout: sizeRatio:  "
            + (viewFinderSrcSize.getWidth() / (float) viewFinderSrcSize.getHeight()));
    log("layout: scaledWidth:  " + scaled.getWidth());
    log("layout: scaledHeight: " + scaled.getHeight());
    log("layout: scaledRatio:  " + (scaled.getWidth() / (float) scaled.getHeight()));
    log(
        "layout: size:       "
            + scaled
            + " ("
            + (scaled.getWidth() / (float) scaled.getHeight())
            + " - "
            + scaleType
            + "-"
            + displayRotationToString(displayRotation)
            + ")");
    log("layout: final       " + layoutL + ", " + layoutT + ", " + layoutR + ", " + layoutB);

    cameraTextureView.layout(layoutL, layoutT, layoutR, layoutB);

    cameraModule.invalidateView();
  }

  /** Records the size of the viewfinder's buffers. */
  @UiThread
  void onViewfinderSourceDimensUpdated(int srcWidth, int srcHeight) {
    if (srcWidth != viewFinderSrcSize.getWidth() || srcHeight != viewFinderSrcSize.getHeight()) {
      viewFinderSrcSize = new Size(srcWidth, srcHeight);
      requestLayout();
    }
  }

  private Size calculateViewfinderViewDimens(
      Size srcSize, int parentWidth, int parentHeight, int displayRotation, ScaleType scaleType) {
    int inWidth = srcSize.getWidth();
    int inHeight = srcSize.getHeight();
    if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) {
      // Need to reverse the width and height since we're in landscape orientation.
      inWidth = srcSize.getHeight();
      inHeight = srcSize.getWidth();
    }

    int outWidth = parentWidth;
    int outHeight = parentHeight;
    if (inWidth != 0 && inHeight != 0) {
      float vfRatio = inWidth / (float) inHeight;
      float parentRatio = parentWidth / (float) parentHeight;

      switch (scaleType) {
        case CENTER_INSIDE:
          // Match longest sides together.
          if (vfRatio > parentRatio) {
            outWidth = parentWidth;
            outHeight = Math.round(parentWidth / vfRatio);
          } else {
            outWidth = Math.round(parentHeight * vfRatio);
            outHeight = parentHeight;
          }
          break;
        case CENTER_CROP:
          // Match shortest sides together.
          if (vfRatio < parentRatio) {
            outWidth = parentWidth;
            outHeight = Math.round(parentWidth / vfRatio);
          } else {
            outWidth = Math.round(parentHeight * vfRatio);
            outHeight = parentHeight;
          }
          break;
      }
    }

    return new Size(outWidth, outHeight);
  }

  /**
   * @return One of {@link Surface#ROTATION_0}, {@link Surface#ROTATION_90}, {@link
   *     Surface#ROTATION_180}, {@link Surface#ROTATION_270}.
   */
  int getDisplaySurfaceRotation() {
    Display display = getDisplay();

    // Null when the View is detached. If we were in the middle of a background operation,
    // better to not NPE. When the background operation finishes, it'll realize that the camera
    // was closed.
    if (display == null) {
      return 0;
    }

    return display.getRotation();
  }

  @UiThread
  SurfaceTexture getSurfaceTexture() {
    if (cameraTextureView != null) {
      return cameraTextureView.getSurfaceTexture();
    }

    return null;
  }

  @UiThread
  void setSurfaceTexture(SurfaceTexture surfaceTexture) {
    if (cameraTextureView.getSurfaceTexture() != surfaceTexture) {
      if (cameraTextureView.isAvailable()) {
        // Remove the old TextureView to properly detach the old SurfaceTexture from the GL Context.
        removeView(cameraTextureView);
        addView(cameraTextureView = new TextureView(getContext()), 0);
        cameraTextureView.setLayerPaint(layerPaint);
        requestLayout();
      }

      cameraTextureView.setSurfaceTexture(surfaceTexture);
    }
  }

  @UiThread
  Matrix getTransform(Matrix matrix) {
    return cameraTextureView.getTransform(matrix);
  }

  @UiThread
  int getViewFinderWidth() {
    return cameraTextureView.getWidth();
  }

  @UiThread
  int getViewFinderHeight() {
    return cameraTextureView.getHeight();
  }

  @UiThread
  void setTransform(final Matrix matrix) {
    if (cameraTextureView != null) {
      cameraTextureView.setTransform(matrix);
    }
  }

  /**
   * Sets the view finder scale type.
   *
   * <p>This controls how the view finder should be scaled and positioned within the view.
   *
   * @param scaleType The desired {@link ScaleType}.
   */
  public void setScaleType(ScaleType scaleType) {
    if (scaleType != this.scaleType) {
      this.scaleType = scaleType;
      requestLayout();
    }
  }

  /**
   * Returns the scale type used to scale the viewfinder.
   *
   * @return The current {@link ScaleType}.
   */
  public ScaleType getScaleType() {
    return scaleType;
  }

  /**
   * Sets the quality for image and video outputs.
   *
   * @param quality The {@link Quality} used for image and video. Currently only {@link
   *     Quality#HIGH} is supported.
   * @throws UnsupportedOperationException if any quality other than HIGH is set.
   * @hide Not currently connected to use cases.
   */
  public void setQuality(Quality quality) {
    cameraModule.setQuality(quality);
  }

  /**
   * Gets the current quality for image and video outputs.
   *
   * @return The current {@link Quality}. Currently only {@link Quality#HIGH} is supported.
   * @hide Not currently connected to use cases.
   */
  public Quality getQuality() {
    return cameraModule.getQuality();
  }

  /**
   * Sets the CameraView capture mode
   *
   * <p>This controls only image or video capture function is enabled or both are enabled.
   *
   * @param captureMode The desired {@link CaptureMode}.
   */
  public void setCaptureMode(CaptureMode captureMode) {
    cameraModule.setCaptureMode(captureMode);
  }

  /**
   * Returns the scale type used to scale the viewfinder.
   *
   * @return The current {@link CaptureMode}.
   */
  public CaptureMode getCaptureMode() {
    return cameraModule.getCaptureMode();
  }

  /**
   * Sets the maximum video duration before {@link OnVideoSavedListener#onVideoSaved(File)}
   * is called automatically. Use {@link #INDEFINITE_VIDEO_DURATION} to disable the timeout.
   */
  private void setMaxVideoDuration(long duration) {
    cameraModule.setMaxVideoDuration(duration);
  }

  /**
   * Returns the maximum duration of videos, or {@link #INDEFINITE_VIDEO_DURATION} if there is no
   * timeout.
   *
   * @hide Not currently implemented.
   */
  @RestrictTo(Scope.LIBRARY_GROUP)
  public long getMaxVideoDuration() {
    return cameraModule.getMaxVideoDuration();
  }

  /**
   * Sets the maximum video size in bytes before {@link
   * OnVideoSavedListener#onVideoSaved(File)} is called automatically. Use {@link
   * #INDEFINITE_VIDEO_SIZE} to disable the size restriction.
   */
  private void setMaxVideoSize(long size) {
    cameraModule.setMaxVideoSize(size);
  }

  /**
   * Returns the maximum size of videos in bytes, or {@link #INDEFINITE_VIDEO_SIZE} if there is no
   * timeout.
   */
  private long getMaxVideoSize() {
    return cameraModule.getMaxVideoSize();
  }

  /**
   * Takes a picture, and calls {@link OnImageCapturedListener#onCaptureSuccess(ImageProxy, int)}
   * once when done.
   *
   * @param listener Listener which will receive success or failure callbacks.
   */
  public void takePicture(OnImageCapturedListener listener) {
    cameraModule.takePicture(listener);
  }

  /**
   * Takes a picture and calls {@link OnImageSavedListener#onImageSaved(File)} when done.
   *
   * @param file The destination.
   * @param listener Listener which will receive success or failure callbacks.
   */
  public void takePicture(File file, OnImageSavedListener listener) {
    cameraModule.takePicture(file, listener);
  }

  /**
   * Takes a video and calls {@link OnVideoSavedListener#onVideoSaved(File)} when done.
   *
   * @param file The destination.
   */
  public void startRecording(File file, OnVideoSavedListener listener) {
    cameraModule.startRecording(file, listener);
  }

  /** Stops an in progress video. */
  public void stopRecording() {
    cameraModule.stopRecording();
  }

  /** @return True if currently recording. */
  public boolean isRecording() {
    return cameraModule.isRecording();
  }

  /**
   * Queries whether the current device has a camera with the specified direction.
   *
   * @return True if the device supports the direction.
   * @throws IllegalStateException if the CAMERA permission is not currently granted.
   */
  @RequiresPermission(permission.CAMERA)
  public boolean hasCameraWithLensFacing(LensFacing lensFacing) {
    return cameraModule.hasCameraWithLensFacing(lensFacing);
  }

  /**
   * Toggles between the primary front facing camera and the primary back facing camera.
   *
   * <p>This will have no effect if not already bound to a lifecycle via {@link
   * #bindToLifecycle(LifecycleOwner)}.
   */
  public void toggleCamera() {
    cameraModule.toggleCamera();
  }

  /**
   * Sets the desired camera lensFacing.
   *
   * <p>This will choose the primary camera with the specified camera lensFacing.
   *
   * <p>If called before {@link #bindToLifecycle(LifecycleOwner)}, this will set the camera to be
   * used when first bound to the lifecycle. If the specified lensFacing is not supported by the
   * device, as determined by {@link #hasCameraWithLensFacing(LensFacing)}, the first supported
   * lensFacing will be chosen when {@link #bindToLifecycle(LifecycleOwner)} is called.
   *
   * <p>If called with {@code null} AFTER binding to the lifecycle, the behavior would be equivalent
   * to unbind the use cases without the lifecycle having to be destroyed.
   *
   * @param lensFacing The desired camera lensFacing.
   */
  public void setCameraByLensFacing(@Nullable LensFacing lensFacing) {
    cameraModule.setCameraByLensFacing(lensFacing);
  }

  /** Returns the currently selected {@link LensFacing}. */
  @Nullable
  public LensFacing getCameraLensFacing() {
    return cameraModule.getLensFacing();
  }

  /**
   * Focuses the camera on the given area.
   *
   * <p>Sets the focus and exposure metering rectangles. Coordinates for both X and Y dimensions are
   * Limited from -1000 to 1000, where (0, 0) is the center of the image and the width/height
   * represent the values from -1000 to 1000.
   *
   * @param focus Area used to focus the camera.
   * @param metering Area used for exposure metering.
   */
  public void focus(Rect focus, Rect metering) {
    cameraModule.focus(focus, metering);
  }

  /** Sets the active flash strategy. */
  public void setFlash(FlashMode flashMode) {
    cameraModule.setFlash(flashMode);
  }

  /** Gets the active flash strategy. */
  public FlashMode getFlash() {
    return cameraModule.getFlash();
  }

  private int getRelativeCameraOrientation(boolean compensateForMirroring) {
    return cameraModule.getRelativeCameraOrientation(compensateForMirroring);
  }

  private long delta() {
    return System.currentTimeMillis() - downEventTimestamp;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    // Disable pinch-to-zoom and tap-to-focus while the camera module is paused.
    if (cameraModule.isPaused()) {
      return false;
    }
    // Only forward the event to the pinch-to-zoom gesture detector when pinch-to-zoom is enabled.
    if (isPinchToZoomEnabled()) {
      pinchToZoomGestureDetector.onTouchEvent(event);
    }
    if (event.getPointerCount() == 2 && isPinchToZoomEnabled() && isZoomSupported()) {
      return true;
    }

    // Camera focus
    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN:
        downEventTimestamp = System.currentTimeMillis();
        break;
      case MotionEvent.ACTION_UP:
        if (delta() < ViewConfiguration.getLongPressTimeout()) {
          upEvent = event;
          performClick();
        }
        break;
      default:
        // Unhandled event.
        return false;
    }
    return true;
  }

  /**
   * Focus the position of the touch event, or focus the center of the viewfinder for accessibility
   * events
   */
  @Override
  public boolean performClick() {
    super.performClick();

    final float x = (upEvent != null) ? upEvent.getX() : getX() + getWidth() / 2f;
    final float y = (upEvent != null) ? upEvent.getY() : getY() + getHeight() / 2f;
    upEvent = null;
    calculateTapArea(focusingRect, x, y, 1f);
    calculateTapArea(meteringRect, x, y, 1.5f);
    if (area(focusingRect) > 0 && area(meteringRect) > 0) {
      focus(focusingRect, meteringRect);
    }

    return true;
  }

  /** Returns the width * height of the given rect */
  private int area(Rect rect) {
    return rect.width() * rect.height();
  }

  /** The area must be between -1000,-1000 and 1000,1000 */
  private void calculateTapArea(Rect rect, float x, float y, float coefficient) {
    int max = 1000;
    int min = -1000;

    // Default to 300 (1/6th the total area) and scale by the coefficient
    int areaSize = (int) (300 * coefficient);

    // Rotate the coordinates if the camera orientation is different
    int width = getWidth();
    int height = getHeight();

    // Compensate orientation as it's mirrored on preview for forward facing cameras
    boolean compensateForMirroring = (getCameraLensFacing() == LensFacing.FRONT);
    int relativeCameraOrientation = getRelativeCameraOrientation(compensateForMirroring);
    int temp;
    float tempf;
    switch (relativeCameraOrientation) {
      case 90:
        // Fall-through
      case 270:
        // We're horizontal. Swap width/height. Swap x/y.
        temp = width;
        //noinspection SuspiciousNameCombination
        width = height;
        height = temp;

        tempf = x;
        //noinspection SuspiciousNameCombination
        x = y;
        y = tempf;
        break;
      default:
        break;
    }

    switch (relativeCameraOrientation) {
      // Map to correct coordinates according to relativeCameraOrientation
      case 90:
        y = height - y;
        break;
      case 180:
        x = width - x;
        y = height - y;
        break;
      case 270:
        x = width - x;
        break;
      default:
        break;
    }

    // Swap x if it's a mirrored preview
    if (compensateForMirroring) {
      x = width - x;
    }

    // Grab the x, y position from within the View and normalize it to -1000 to 1000
    x = min + distance(max, min) * (x / width);
    y = min + distance(max, min) * (y / height);

    // Modify the rect to the bounding area
    rect.top = (int) y - areaSize / 2;
    rect.left = (int) x - areaSize / 2;
    rect.bottom = rect.top + areaSize;
    rect.right = rect.left + areaSize;

    // Cap at -1000 to 1000
    rect.top = rangeLimit(rect.top, max, min);
    rect.left = rangeLimit(rect.left, max, min);
    rect.bottom = rangeLimit(rect.bottom, max, min);
    rect.right = rangeLimit(rect.right, max, min);
  }

  private int rangeLimit(int val, int max, int min) {
    return Math.min(Math.max(val, min), max);
  }

  private float rangeLimit(float val, float max, float min) {
    return Math.min(Math.max(val, min), max);
  }

  private int distance(int a, int b) {
    return Math.abs(a - b);
  }

  /**
   * Sets whether the view should allow pinch-to-zoom.
   *
   * <p>When enabled, the user can pinch the camera to zoom in/out. This only has an effect if the
   * bound camera supports zoom.
   *
   * @param enabled True to enable pinch-to-zoom.
   */
  public void setPinchToZoomEnabled(boolean enabled) {
    isPinchToZoomEnabled = enabled;
  }

  /**
   * Returns whether the view allows pinch-to-zoom.
   *
   * @return True if pinch to zoom is enabled.
   */
  public boolean isPinchToZoomEnabled() {
    return isPinchToZoomEnabled;
  }

  /**
   * Sets the current zoom level.
   *
   * <p>Valid zoom values range from 1 to {@link #getMaxZoomLevel()}.
   *
   * @param zoomLevel The requested zoom level.
   */
  public void setZoomLevel(float zoomLevel) {
    cameraModule.setZoomLevel(zoomLevel);
  }

  /**
   * Returns the current zoom level.
   *
   * @return The current zoom level.
   */
  public float getZoomLevel() {
    return cameraModule.getZoomLevel();
  }

  /**
   * Returns the minimum zoom level.
   *
   * <p>For most cameras this should return a zoom level of 1. A zoom level of 1 corresponds to a
   * non-zoomed image.
   *
   * @return The minimum zoom level.
   */
  public float getMinZoomLevel() {
    return cameraModule.getMinZoomLevel();
  }

  /**
   * Returns the maximum zoom level.
   *
   * <p>The zoom level corresponds to the ratio between both the widths and heights of a non-zoomed
   * image and a maximally zoomed image for the selected camera.
   *
   * @return The maximum zoom level.
   */
  public float getMaxZoomLevel() {
    return cameraModule.getMaxZoomLevel();
  }

  /**
   * Returns whether the bound camera supports zooming.
   *
   * @return True if the camera supports zooming.
   */
  public boolean isZoomSupported() {
    return cameraModule.isZoomSupported();
  }

  /**
   * Turns on/off torch.
   *
   * @param torch True to turn on torch, false to turn off torch.
   */
  public void enableTorch(boolean torch) {
    cameraModule.enableTorch(torch);
  }

  /**
   * Returns current torch status.
   * 
   * @return true if torch is on , otherwise false
   */
  public boolean isTorchOn() {
    return cameraModule.isTorchOn();
  }

  private class PinchToZoomGestureDetector extends ScaleGestureDetector
      implements ScaleGestureDetector.OnScaleGestureListener {
    private static final float SCALE_MULTIPIER = 0.75f;
    private final BaseInterpolator interpolator = new DecelerateInterpolator(2f);
    private float normalizedScaleFactor = 0;

    PinchToZoomGestureDetector(Context context) {
      this(context, new S());
    }

    PinchToZoomGestureDetector(Context context, S s) {
      super(context, s);
      s.setRealGestureDetector(this);
    }

    @Override
    public boolean onScale(ScaleGestureDetector detector) {
      normalizedScaleFactor += (detector.getScaleFactor() - 1f) * SCALE_MULTIPIER;
      // Since the scale factor is normalized, it should always be in the range [0, 1]
      normalizedScaleFactor = rangeLimit(normalizedScaleFactor, 1f, 0);

      // Apply decelerate interpolation. This will cause the differences to seem less pronounced
      // at higher zoom levels.
      float transformedScale = interpolator.getInterpolation(normalizedScaleFactor);

      // Transform back from normalized coordinates to the zoom scale
      float zoomLevel =
          (getMaxZoomLevel() == getMinZoomLevel())
              ? getMinZoomLevel()
              : getMinZoomLevel() + transformedScale * (getMaxZoomLevel() - getMinZoomLevel());

      setZoomLevel(rangeLimit(zoomLevel, getMaxZoomLevel(), getMinZoomLevel()));
      return true;
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector detector) {
      float initialZoomLevel = getZoomLevel();
      normalizedScaleFactor =
          (getMaxZoomLevel() == getMinZoomLevel())
              ? 0
              : (initialZoomLevel - getMinZoomLevel()) / (getMaxZoomLevel() - getMinZoomLevel());
      return true;
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {}
  }

  private static class S extends ScaleGestureDetector.SimpleOnScaleGestureListener {
    private ScaleGestureDetector.OnScaleGestureListener listener;

    void setRealGestureDetector(ScaleGestureDetector.OnScaleGestureListener l) {
      listener = l;
    }

    @Override
    public boolean onScale(ScaleGestureDetector detector) {
      return listener.onScale(detector);
    }
  }

  /** Debug logging that can be enabled. */
  private static void log(String msg) {
    if (DEBUG) {
      Log.i(TAG, msg);
    }
  }

  /** Utility method for converting an displayRotation int into a human readable string. */
  private static String displayRotationToString(int displayRotation) {
    if (displayRotation == Surface.ROTATION_0 || displayRotation == Surface.ROTATION_180) {
      return "Portrait-" + (displayRotation * 90);
    } else if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) {
      return "Landscape-" + (displayRotation * 90);
    } else {
      return "Unknown";
    }
  }
}
