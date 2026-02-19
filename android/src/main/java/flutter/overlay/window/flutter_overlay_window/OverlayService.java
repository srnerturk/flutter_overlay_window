package flutter.overlay.window.flutter_overlay_window;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.app.PendingIntent;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.FlutterEngineGroup;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.plugin.common.MethodChannel;

public class OverlayService extends Service implements View.OnTouchListener {
    private Resources mResources;
    public static final String INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow";
    public static boolean isRunning = false;
    private WindowManager windowManager = null;
    private FlutterView flutterView;
    private MethodChannel flutterChannel;
    private BasicMessageChannel<Object> overlayMessageChannel;
    private static OverlayService instance;
    
    // Flagleri basitleştirdik, ekran sınırlarını tanıması için
    private int clickableFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | 
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | 
                                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;

    private Handler mAnimationHandler = new Handler();
    private float lastX, lastY;
    private int lastYPosition;
    private boolean dragging;
    private static final float MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f;
    private Point szWindow = new Point();
    private Timer mTrayAnimationTimer;
    private TrayAnimationTimerTask mTrayTimerTask;

    // Helper: Status Bar Yüksekliği
    private int getStatusBarHeight() {
        int result = 0;
        int resourceId = mResources.getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = mResources.getDimensionPixelSize(resourceId);
        }
        return result;
    }
    
    // Helper: Navigation Bar Yüksekliği
    private int getNavigationBarHeight() {
        int result = 0;
        int resourceId = mResources.getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = mResources.getDimensionPixelSize(resourceId);
        }
        return result;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDestroy() {
        if (windowManager != null) {
            windowManager.removeView(flutterView);
            windowManager = null;
            flutterView.detachFromFlutterEngine();
            flutterView = null;
        }
        isRunning = false;
        NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(OverlayConstants.NOTIFICATION_ID);
        instance = null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateScreenDimensions();
    }

    private void updateScreenDimensions() {
        if (windowManager != null) {
            DisplayMetrics displaymetrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(displaymetrics);
            szWindow.set(displaymetrics.widthPixels, displaymetrics.heightPixels);
        }
    }

    private void saveLastPosition(int x, int y) {
        SharedPreferences sharedPref = getSharedPreferences("OverlayPrefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putInt("last_x", x);
        editor.putInt("last_y", y);
        editor.putBoolean("is_positioned", true);
        editor.apply();
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        
        mResources = getApplicationContext().getResources();
        int startX = intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY);
        int startY = intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY);
        boolean usePixelCoordinates = intent.getBooleanExtra("usePixelCoordinates", false);

        boolean isCloseWindow = intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false);
        if (isCloseWindow) {
            if (windowManager != null) {
                windowManager.removeView(flutterView);
                windowManager = null;
                flutterView.detachFromFlutterEngine();
                stopSelf();
            }
            isRunning = false;
            return START_STICKY;
        }
        if (windowManager != null) {
            windowManager.removeView(flutterView);
            windowManager = null;
            flutterView.detachFromFlutterEngine();
            stopSelf();
        }
        isRunning = true;

        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        engine.getLifecycleChannel().appIsResumed();
        flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
        flutterView.attachToFlutterEngine(FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG));
        flutterView.setFitsSystemWindows(true);
        flutterView.setFocusable(true);
        flutterView.setFocusableInTouchMode(true);
        flutterView.setBackgroundColor(Color.TRANSPARENT);

        flutterChannel.setMethodCallHandler((call, result) -> {
            if (call.method.equals("updateFlag")) {
                String flag = call.argument("flag").toString();
                updateOverlayFlag(result, flag);
            } else if (call.method.equals("updateOverlayPosition")) {
                int x = call.<Integer>argument("x");
                int y = call.<Integer>argument("y");
                moveOverlay(x, y, result);
            } else if (call.method.equals("resizeOverlay")) {
                int width = call.argument("width");
                int height = call.argument("height");
                boolean enableDrag = call.argument("enableDrag");
                resizeOverlay(width, height, enableDrag, result);
            }
        });
        overlayMessageChannel.setMessageHandler((message, reply) -> {
            WindowSetup.messenger.send(message);
        });

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        updateScreenDimensions();

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowSetup.width == -1999 ? -1 : WindowSetup.width,
                WindowSetup.height != -1999 ? WindowSetup.height : -1,
                0, 0,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowSetup.flag,
                PixelFormat.TRANSLUCENT
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
            params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
        }

        // --- KONUMLANDIRMA MANTIĞI ---
        params.gravity = WindowSetup.gravity;
        
        // Koordinat Atama Kısmı:
        if (usePixelCoordinates) {
            // Zaten Pixel (Hafızadan geldi veya lastPosition seçildi) -> Direkt ata
            // constrainToScreenBounds metodundan geçirmeyi unutma!
            int[] constrained = constrainToScreenBounds(startX, startY, params);
            params.x = constrained[0];
            params.y = constrained[1];
        } else {
            // DP (Flutter'dan geldi) -> Pixel'e çevir
            int pixelX = (startX == OverlayConstants.DEFAULT_XY) ? 0 : dpToPx(startX);
            int pixelY = (startY == OverlayConstants.DEFAULT_XY) ? getStatusBarHeight() : dpToPx(startY);
            
            // İlk pozisyonu da sınırla
            if ((params.gravity & Gravity.TOP) == Gravity.TOP && (params.gravity & Gravity.LEFT) == Gravity.LEFT) {
                int[] constrained = constrainToScreenBounds(pixelX, pixelY, params);
                params.x = constrained[0];
                params.y = constrained[1];
            } else {
                params.x = pixelX;
                params.y = pixelY;
            }
        }

        flutterView.setOnTouchListener(this);
        windowManager.addView(flutterView, params);
        return START_STICKY;
    }

    // ... Diğer metodlar aynı kalabilir ...
    
    private void updateOverlayFlag(MethodChannel.Result result, String flag) {
        if (windowManager != null) {
            WindowSetup.setFlag(flag);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            params.flags = WindowSetup.flag;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
                params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
            } else { params.alpha = 1; }
            windowManager.updateViewLayout(flutterView, params);
            result.success(true);
        } else { result.success(false); }
    }

    private void resizeOverlay(int width, int height, boolean enableDrag, MethodChannel.Result result) {
        if (windowManager != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            params.width = (width == -1999 || width == -1) ? -1 : dpToPx(width);
            params.height = (height != 1999 || height != -1) ? dpToPx(height) : height;
            WindowSetup.enableDrag = enableDrag;
            windowManager.updateViewLayout(flutterView, params);
            result.success(true);
        } else { result.success(false); }
    }

    private void moveOverlay(int x, int y, MethodChannel.Result result) {
        if (windowManager != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            int pxX = (x == -1999 || x == -1) ? -1 : dpToPx(x);
            int pxY = dpToPx(y);
            if (pxX != -1 && pxY != -1) {
                int[] constrainedPos = constrainToScreenBounds(pxX, pxY, params);
                params.x = constrainedPos[0];
                params.y = constrainedPos[1];
            } else {
                params.x = pxX;
                params.y = pxY;
            }
            windowManager.updateViewLayout(flutterView, params);
            if (result != null) result.success(true);
        } else {
            if (result != null) result.success(false);
        }
    }
    
    public static boolean moveOverlay(int x, int y) {
        if (instance != null && instance.flutterView != null && instance.windowManager != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
            int pxX = (x == -1999 || x == -1) ? -1 : instance.dpToPx(x);
            int pxY = instance.dpToPx(y);
            if (pxX != -1 && pxY != -1) {
                int[] constrainedPos = instance.constrainToScreenBounds(pxX, pxY, params);
                params.x = constrainedPos[0];
                params.y = constrainedPos[1];
            } else {
                params.x = pxX;
                params.y = pxY;
            }
            instance.windowManager.updateViewLayout(instance.flutterView, params);
            return true;
        }
        return false;
    }

    public static Map<String, Double> getCurrentPosition() {
        if (instance != null && instance.flutterView != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
            Map<String, Double> position = new HashMap<>();
            position.put("x", instance.pxToDp(params.x));
            position.put("y", instance.pxToDp(params.y));
            return position;
        }
        return null;
    }

    @Override
    public void onCreate() {
        FlutterEngine flutterEngine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        if (flutterEngine == null) {
            FlutterEngineGroup engineGroup = new FlutterEngineGroup(this);
            DartExecutor.DartEntrypoint entryPoint = new DartExecutor.DartEntrypoint(
                FlutterInjector.instance().flutterLoader().findAppBundlePath(), "overlayMain");
            flutterEngine = engineGroup.createAndRunEngine(this, entryPoint);
            FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, flutterEngine);
        }
        if (flutterEngine != null) {
            flutterChannel = new MethodChannel(flutterEngine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
            overlayMessageChannel = new BasicMessageChannel(flutterEngine.getDartExecutor(), OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
        }
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, FlutterOverlayWindowPlugin.class);
        int pendingFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);
        final int notifyIcon = getDrawableResourceId("mipmap", "launcher");
        Notification notification = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                .setContentTitle(WindowSetup.overlayTitle)
                .setContentText(WindowSetup.overlayContent)
                .setSmallIcon(notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon)
                .setContentIntent(pendingIntent)
                .setVisibility(WindowSetup.notificationVisibility)
                .build();
        startForeground(OverlayConstants.NOTIFICATION_ID, notification);
        instance = this;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    OverlayConstants.CHANNEL_ID, "Foreground Service Channel", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = getSystemService(NotificationManager.class);
            assert manager != null;
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private int getDrawableResourceId(String resType, String name) {
        return getApplicationContext().getResources().getIdentifier(String.format("ic_%s", name), resType, getApplicationContext().getPackageName());
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, Float.parseFloat(dp + ""), mResources.getDisplayMetrics());
    }
    
    private double pxToDp(int px) {
        return (double) px / mResources.getDisplayMetrics().density;
    }

    // --- GÜVENLİ ALAN SINIRLAMASI ---
    private int[] constrainToScreenBounds(int x, int y, WindowManager.LayoutParams params) {
        updateScreenDimensions(); 
        
        int screenWidth = szWindow.x;
        int screenHeight = szWindow.y;

        int overlayWidth = (flutterView != null && flutterView.getWidth() > 0) ? flutterView.getWidth() : params.width;
        int overlayHeight = (flutterView != null && flutterView.getHeight() > 0) ? flutterView.getHeight() : params.height;

        if (overlayWidth <= 0) overlayWidth = dpToPx(50);
        if (overlayHeight <= 0) overlayHeight = dpToPx(50);

        int minY = getStatusBarHeight();
        int maxY = screenHeight - getNavigationBarHeight() - overlayHeight;
        int maxX = screenWidth - overlayWidth;

        int constrainedX = Math.max(0, Math.min(x, maxX));
        int constrainedY = Math.max(minY, Math.min(y, maxY));

        return new int[]{constrainedX, constrainedY};
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (windowManager != null && WindowSetup.enableDrag) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = false;
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - lastX;
                    float dy = event.getRawY() - lastY;
                    if (!dragging && dx * dx + dy * dy < 25) return false;
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    
                    // Gravity'e göre yön kontrolü
                    // Eğer Gravity TOP|LEFT ise invert'e gerek yok
                    boolean invertX = (params.gravity & Gravity.RIGHT) == Gravity.RIGHT;
                    
                    int xx = params.x + (int) (dx * (invertX ? -1 : 1));
                    int yy = params.y + (int) dy;
                    
                    // Her harekette sınırları kontrol et
                    int[] constrainedPos = constrainToScreenBounds(xx, yy, params);
                    
                    // Sadece Gravity TOP|LEFT ise bu sınırlandırmayı uygula, 
                    // yoksa Center gravity ile bu hesaplama bozulur.
                    // Ama drag başladığında genelde TOP|LEFT'e dönüştürmek en iyisidir.
                    
                    params.x = constrainedPos[0];
                    params.y = constrainedPos[1];
                    
                    windowManager.updateViewLayout(flutterView, params);
                    dragging = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // Sürükleme bittiğinde, artık "Positioned" (Konumlandırılmış) kabul et
                    // Ve bu koordinatları kaydet.
                    saveLastPosition(params.x, params.y);
                    lastYPosition = params.y;
                    
                    // Eğer kullanıcı bir kez sürüklediyse, bundan sonra hep TOP|LEFT kullanacağız demektir.
                    // SharedPreferences "is_positioned" = true oldu.
                    
                    if (!WindowSetup.positionGravity.equals("none")) {
                         // Animasyon...
                    }
                    return false;
            }
            return false;
        }
        return false;
    }
    
     private class TrayAnimationTimerTask extends TimerTask {
        int mDestX; int mDestY;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
        public TrayAnimationTimerTask() {
            super();
            mDestY = lastYPosition;
            switch (WindowSetup.positionGravity) {
                case "auto": mDestX = (params.x + (flutterView.getWidth() / 2)) <= szWindow.x / 2 ? 0 : szWindow.x - flutterView.getWidth(); return;
                case "left": mDestX = 0; return;
                case "right": mDestX = szWindow.x - flutterView.getWidth(); return;
                default: mDestX = params.x; mDestY = params.y; break;
            }
        }
        @Override
        public void run() {
            mAnimationHandler.post(() -> {
                int newX = (2 * (params.x - mDestX)) / 3 + mDestX;
                int newY = (2 * (params.y - mDestY)) / 3 + mDestY;
                int[] safePos = constrainToScreenBounds(newX, newY, params);
                params.x = safePos[0];
                params.y = safePos[1];
                if (windowManager != null && flutterView != null) { windowManager.updateViewLayout(flutterView, params); }
                if (Math.abs(params.x - mDestX) < 2 && Math.abs(params.y - mDestY) < 2) { this.cancel(); if (mTrayAnimationTimer != null) mTrayAnimationTimer.cancel(); }
            });
        }
    }
}