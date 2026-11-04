package com.smartiot.securitycam;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public class SecurityNotificationHelper {

    private static final String CHANNEL_ID = "security_motion_alerts";
    private static final String CHANNEL_NAME = "Cảnh Báo Đột Nhập Camera";
    private final Context context;
    private final NotificationManager notificationManager;

    public SecurityNotificationHelper(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Thông báo khẩn cấp khi cảm biến PIR phát hiện chuyển động");
            channel.enableVibration(true);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    public void showMotionAlertNotification(String time) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("🚨 CẢNH BÁO AN NINH: CÓ CHUYỂN ĐỘNG!")
                .setContentText("Cảm biến PIR phát hiện có người lúc: " + time + ". Bấm để xem hình ảnh.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)
                .setVibrate(new long[]{0, 500, 200, 500, 200, 500});

        if (notificationManager != null) {
            notificationManager.notify(202, builder.build());
        }
    }
}
