package flutter.overlay.window.flutter_overlay_window;

import android.view.Gravity;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;
import io.flutter.plugin.common.BasicMessageChannel;

public abstract class WindowSetup {

    static int height = WindowManager.LayoutParams.MATCH_PARENT;
    static int width = WindowManager.LayoutParams.MATCH_PARENT;
    // Varsayılan flagleri ekran sınırlarına saygılı olacak şekilde ayarladık
    static int flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
    
    // Varsayılan gravity TOP|LEFT olmalı ki X,Y koordinatları sol-üstten hesaplansın
    static int gravity = Gravity.TOP | Gravity.LEFT;
    
    static BasicMessageChannel<Object> messenger = null;
    static String overlayTitle = "Overlay is activated";
    static String overlayContent = "Tap to edit settings or disable";
    static String positionGravity = "none";
    static int notificationVisibility = NotificationCompat.VISIBILITY_PRIVATE;
    static boolean enableDrag = false;

    static void setNotificationVisibility(String name) {
        if (name.equalsIgnoreCase("visibilityPublic")) {
            notificationVisibility = NotificationCompat.VISIBILITY_PUBLIC;
        } else if (name.equalsIgnoreCase("visibilitySecret")) {
            notificationVisibility = NotificationCompat.VISIBILITY_SECRET;
        } else {
            notificationVisibility = NotificationCompat.VISIBILITY_PRIVATE;
        }
    }

    static void setFlag(String name) {
        // FLAG_LAYOUT_NO_LIMITS kaldırıldı, artık sınırlar çalışacak
        int baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | 
                       WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;

        if (name.equalsIgnoreCase("flagNotFocusable") || name.equalsIgnoreCase("defaultFlag")) {
            flag = baseFlags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else if (name.equalsIgnoreCase("flagNotTouchable") || name.equalsIgnoreCase("clickThrough")) {
            flag = baseFlags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | 
                   WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else if (name.equalsIgnoreCase("flagNotTouchModal") || name.equalsIgnoreCase("focusPointer")) {
            flag = baseFlags | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        } else {
            flag = baseFlags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
    }

    static void setGravityFromAlignment(String alignment) {
        if (alignment == null) return;
        
        if (alignment.equalsIgnoreCase("topLeft")) {
            gravity = Gravity.TOP | Gravity.LEFT;
        } else if (alignment.equalsIgnoreCase("topCenter")) {
            gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        } else if (alignment.equalsIgnoreCase("topRight")) {
            gravity = Gravity.TOP | Gravity.RIGHT;
        } else if (alignment.equalsIgnoreCase("centerLeft")) {
            gravity = Gravity.CENTER_VERTICAL | Gravity.LEFT;
        } else if (alignment.equalsIgnoreCase("center")) {
            gravity = Gravity.CENTER;
        } else if (alignment.equalsIgnoreCase("centerRight")) {
            gravity = Gravity.CENTER_VERTICAL | Gravity.RIGHT;
        } else if (alignment.equalsIgnoreCase("bottomLeft")) {
            gravity = Gravity.BOTTOM | Gravity.LEFT;
        } else if (alignment.equalsIgnoreCase("bottomCenter")) {
            gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        } else if (alignment.equalsIgnoreCase("bottomRight")) {
            gravity = Gravity.BOTTOM | Gravity.RIGHT;
        }
    }
}
