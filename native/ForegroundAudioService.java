package com.hdklinik.isitme;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class ForegroundAudioService extends Service {
  static final String CH_ID = "ses_yardimcisi";
  static final int NOTIF_ID = 42;

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel ch = new NotificationChannel(CH_ID, "Ses Yardımcısı", NotificationManager.IMPORTANCE_LOW);
      getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Notification n = buildNotification();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    } else {
      startForeground(NOTIF_ID, n);
    }
    return START_STICKY;
  }

  private Notification buildNotification() {
    Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
    if (open == null) open = new Intent();
    PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
    return new NotificationCompat.Builder(this, CH_ID)
        .setContentTitle("Ses Yardımcısı çalışıyor")
        .setContentText("Dinleme etkin")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentIntent(pi)
        .setOngoing(true)
        .build();
  }
}
