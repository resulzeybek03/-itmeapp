package com.hdklinik.isitme;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class ForegroundAudioService extends Service {
  static final String CH_ID = "ses_yardimcisi";
  static final int NOTIF_ID = 42;
  static final String ACTION_STOP = "com.hdklinik.isitme.STOP";

  private BroadcastReceiver noisyReceiver;

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
    // Kulaklık çıkarılınca (ses hoparlöre geçmeye çalışınca) otomatik durdur
    noisyReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        AudioEngine.get().requestStop("unplug");
      }
    };
    registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    // Bildirimdeki "Durdur" düğmesi
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
      AudioEngine.get().stop();
      stopSelf();
      return START_NOT_STICKY;
    }

    Notification n = buildNotification();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    } else {
      startForeground(NOTIF_ID, n);
    }

    AudioEngine.get().setContext(this);
    String params = intent != null ? intent.getStringExtra("params") : null;
    boolean ok = AudioEngine.get().start(params);
    if (!ok) stopSelf();
    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
    AudioEngine.get().stop();
    super.onDestroy();
  }

  private Notification buildNotification() {
    Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
    if (open == null) open = new Intent();
    PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);

    Intent stopIntent = new Intent(this, ForegroundAudioService.class);
    stopIntent.setAction(ACTION_STOP);
    PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);

    return new NotificationCompat.Builder(this, CH_ID)
        .setContentTitle("Ses Yardımcısı çalışıyor")
        .setContentText("Dinleme etkin")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentIntent(pi)
        .addAction(android.R.drawable.ic_media_pause, "Durdur", stopPi)
        .setOngoing(true)
        .build();
  }
}
