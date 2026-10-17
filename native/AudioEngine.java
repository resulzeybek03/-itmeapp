package com.hdklinik.isitme;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;

import org.json.JSONObject;

/**
 * Ses motoru: mikrofon -> sessizlik kapısı -> ton filtreleri -> çok bantlı sıkıştırma
 * -> uzak ses yükseltme -> kazanç -> sınırlayıcı -> kulaklık.
 */
public class AudioEngine {

  public interface Listener {
    void onGate(boolean open);
    void onLevel(float level);        // çıkış seviyesi
    void onInputLevel(float level);   // giriş seviyesi (kapı öncesi, kalibrasyon için)
    void onError(String message);
    void onStopped(String reason);    // user | unplug | focus | error
    void onLatency(float ms);
  }

  private static final AudioEngine INSTANCE = new AudioEngine();
  public static AudioEngine get() { return INSTANCE; }

  private static final int SR = 48000;
  private static final int BLOCK = 128;
  private static final float LIMIT_DB = -6f;
  private static final float MAX_OUT = 0.5f;

  private static Context appCtx;
  private AudioManager audioMgr;
  private AudioManager.OnAudioFocusChangeListener focusListener;

  private volatile Listener listener;
  private volatile boolean running = false;
  private volatile boolean filtersDirty = true;
  private volatile String stopReason = "user";
  private Thread thread;

  // Ayarlar (JS'den gelir)
  private volatile float gateDb = -42f;
  private volatile boolean muteFully = true;
  private volatile float gain = 3f;
  private volatile float midDb = 8f;
  private volatile float trebleDb = 10f;
  private volatile float cutHz = 180f;
  private volatile float pan = 0f;
  private volatile float far = 0.5f;

  // DSP durumu (yalnızca ses thread'i kullanır)
  private float thOpen, thClose, floorG;
  private float env = 0f, gateG = 0f;
  private boolean gateOpen = false;
  private int hold = 0;
  private final int holdN = (int) (0.35 * SR);
  private float oEnv = 0f, uGain = 1f, lEnv = 0f, lGain = 1f;
  private final Biquad hp = new Biquad(), mud = new Biquad(), mid = new Biquad(), treble = new Biquad();
  private final Biquad xLoLp = new Biquad(), xMidHp = new Biquad(), xMidLp = new Biquad(), xHiHp = new Biquad();
  private final float[] bEnv = new float[3];
  private final float[] bGain = {1f, 1f, 1f};

  private final float eAtt = coef(0.002f), eRel = coef(0.12f);
  private final float gAtt = coef(0.002f), gRel = coef(0.25f);
  private final float cAtt = coef(0.004f), cRel = coef(0.15f);
  private final float uAtt = coef(0.05f), uRel = coef(0.3f);
  private final float lAtt = coef(0.001f), lRel = coef(0.10f);

  private static float coef(float seconds) {
    return 1f - (float) Math.exp(-1.0 / (seconds * SR));
  }

  public void setListener(Listener l) { listener = l; }

  public void setContext(Context c) { appCtx = c.getApplicationContext(); }

  public boolean isRunning() { return running; }

  public void setParams(String json) {
    try {
      JSONObject o = new JSONObject(json);
      if (o.has("gate")) gateDb = (float) o.getDouble("gate");
      if (o.has("muteFully")) muteFully = o.getBoolean("muteFully");
      if (o.has("gain")) gain = Math.min(10f, (float) o.getDouble("gain"));
      if (o.has("mid")) midDb = (float) o.getDouble("mid");
      if (o.has("treble")) trebleDb = (float) o.getDouble("treble");
      if (o.has("cut")) cutHz = (float) o.getDouble("cut");
      if (o.has("pan")) pan = (float) o.getDouble("pan");
      if (o.has("far")) far = Math.max(0f, Math.min(1f, (float) o.getDouble("far")));
      filtersDirty = true;
    } catch (Exception e) {
      emitError("Ayar okunamadı: " + e.getMessage());
    }
  }

  public synchronized boolean start(String json) {
    if (running) return true;
    if (json != null) setParams(json);

    int inMin = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    int outMin = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
    if (inMin <= 0 || outMin <= 0) {
      emitError("Ses donanımı desteklenmiyor");
      return false;
    }

    int recBuf = Math.max(inMin, BLOCK * 4) * 2;
    int trBuf = Math.max(outMin, BLOCK * 4 * 2 * 2);
    AudioAttributes attrs = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build();
    AudioFormat inFmt = new AudioFormat.Builder()
        .setSampleRate(SR)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build();
    AudioFormat outFmt = new AudioFormat.Builder()
        .setSampleRate(SR)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build();

    AudioRecord rec;
    AudioTrack track;
    try {
      if (Build.VERSION.SDK_INT >= 26) {
        rec = new AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(inFmt)
            .setBufferSizeInBytes(recBuf)
            .build();
        track = new AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(outFmt)
            .setBufferSizeInBytes(trBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build();
      } else {
        rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuf);
        track = new AudioTrack(attrs, outFmt, trBuf, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE);
      }
    } catch (Exception e) {
      emitError("Ses motoru oluşturulamadı: " + e.getMessage());
      return false;
    }

    // Kablolu kulaklığın mikrofonu yerine telefonun kendi mikrofonunu kullan
    if (appCtx != null && Build.VERSION.SDK_INT >= 23) {
      AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
      if (am != null) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
          if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
            rec.setPreferredDevice(d);
            break;
          }
        }
      }
    }

    if (rec.getState() != AudioRecord.STATE_INITIALIZED || track.getState() != AudioTrack.STATE_INITIALIZED) {
      rec.release();
      track.release();
      emitError("Mikrofon başlatılamadı (izin verilmiş mi?)");
      return false;
    }

    env = 0f; gateG = 0f; gateOpen = false; hold = 0;
    oEnv = 0f; uGain = 1f; lEnv = 0f; lGain = 1f;
    java.util.Arrays.fill(bEnv, 0f);
    java.util.Arrays.fill(bGain, 1f);
    filtersDirty = true;
    stopReason = "user";
    running = true;

    // Tahmini gecikme: giriş ve çıkış tamponlarının toplamı
    float estMs = ((recBuf / 2f) + (trBuf / 4f)) / SR * 1000f;
    emitLatency(estMs);

    rec.startRecording();
    track.play();
    requestFocus();

    thread = new Thread(() -> loop(rec, track), "ses-motoru");
    thread.setPriority(Thread.MAX_PRIORITY);
    thread.start();
    return true;
  }

  /** Kullanıcı durdurdu */
  public void stop() {
    stopReason = "user";
    running = false;
    joinThread();
  }

  /** Sistem ya da olay nedeniyle durdur (kulaklık çıkarıldı, ses odağı kaybı vb.) */
  public void requestStop(String reason) {
    stopReason = reason;
    running = false;
  }

  private void joinThread() {
    Thread t = thread;
    thread = null;
    if (t != null && t != Thread.currentThread()) {
      try { t.join(1500); } catch (InterruptedException ignored) {}
    }
  }

  private void requestFocus() {
    if (appCtx == null) return;
    audioMgr = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
    if (audioMgr == null) return;
    focusListener = change -> {
      // Arama ya da başka bir uygulama sesi aldığında otomatik durdur, kullanıcı tekrar başlatır
      if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
        requestStop("focus");
      }
    };
    audioMgr.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
  }

  private void releaseFocus() {
    if (audioMgr != null && focusListener != null) {
      audioMgr.abandonAudioFocus(focusListener);
    }
    focusListener = null;
    audioMgr = null;
  }

  private void loop(AudioRecord rec, AudioTrack track) {
    short[] in = new short[BLOCK];
    short[] out = new short[BLOCK * 2];
    long lastLevel = 0;
    float outPeak = 0f, inPeak = 0f;
    try {
      while (running) {
        int n = rec.read(in, 0, BLOCK);
        if (n < 0) {
          emitError("Mikrofon okuma hatası: " + n);
          stopReason = "error";
          break;
        }
        if (n == 0) continue;
        if (filtersDirty) { computeParams(); filtersDirty = false; }

        float bl = pan > 0 ? 1f - pan : 1f;
        float br = pan < 0 ? 1f + pan : 1f;
        for (int i = 0; i < n; i++) {
          float raw = in[i] / 32768f;
          float ia = Math.abs(raw);
          if (ia > inPeak) inPeak = ia;
          float x = processSample(raw);
          float a = Math.abs(x);
          if (a > outPeak) outPeak = a;
          out[2 * i] = toShort(x * bl);
          out[2 * i + 1] = toShort(x * br);
        }
        track.write(out, 0, n * 2);

        long now = System.currentTimeMillis();
        if (now - lastLevel > 100) {
          emitInputLevel(inPeak);
          emitLevel(outPeak);
          inPeak = 0f;
          outPeak = 0f;
          lastLevel = now;
        }
      }
    } finally {
      running = false;
      try { rec.stop(); } catch (Exception ignored) {}
      try { track.stop(); } catch (Exception ignored) {}
      rec.release();
      track.release();
      releaseFocus();
      emitStopped(stopReason);
    }
  }

  private void computeParams() {
    hp.highpass(Math.max(40, cutHz), 0.707);
    mud.peaking(320, cutHz > 150 ? -6 : -2, 1.1);
    mid.peaking(2400, midDb, 0.9);
    treble.peaking(4500, trebleDb, 0.8);
    xLoLp.lowpass(500, 0.5);
    xMidHp.highpass(500, 0.5);
    xMidLp.lowpass(3000, 0.5);
    xHiHp.highpass(3000, 0.5);
    thOpen = (float) Math.pow(10, gateDb / 20.0);
    thClose = (float) Math.pow(10, (gateDb - 10) / 20.0);
    floorG = muteFully ? 0f : 0.01f;
  }

  private float processSample(float x) {
    // 1) Sessizlik kapısı
    float a = Math.abs(x);
    env += (a - env) * (a > env ? eAtt : eRel);
    boolean was = gateOpen;
    if (env > thOpen) {
      gateOpen = true; hold = holdN;
    } else if (gateOpen) {
      if (env >= thClose) hold = holdN;
      else if (--hold <= 0) gateOpen = false;
    }
    if (gateOpen != was) emitGate(gateOpen);
    float target = gateOpen ? 1f : floorG;
    gateG += (target - gateG) * (target > gateG ? gAtt : gRel);
    x *= gateG;

    // 2) Ton filtreleri
    x = hp.process(x);
    x = mud.process(x);
    x = mid.process(x);
    x = treble.process(x);

    // 3) Çok bantlı sıkıştırma (düşük / orta / yüksek)
    float lo = xLoLp.process(x);
    float md = xMidLp.process(xMidHp.process(x));
    float hi = xHiHp.process(x);
    lo *= bandGain(0, lo);
    md *= bandGain(1, md);
    hi *= bandGain(2, hi);
    x = lo + md + hi;

    // 4) Uzak / düşük ses yükseltme (konuşma açıkken)
    a = Math.abs(x);
    oEnv += (a - oEnv) * (a > oEnv ? cAtt : cRel);
    float lvl = 20f * (float) Math.log10(oEnv + 1e-9f);
    float uDb = 0f;
    if (far > 0f && gateOpen && lvl < -32f) uDb = Math.min(12f, (-32f - lvl) * far);
    float uTarget = (float) Math.pow(10, uDb / 20.0);
    uGain += (uTarget - uGain) * (uTarget > uGain ? uAtt : uRel);
    x *= uGain;

    // 5) Kazanç
    x *= gain;

    // 6) Sınırlayıcı
    a = Math.abs(x);
    lEnv += (a - lEnv) * (a > lEnv ? lAtt : lRel);
    float lvl2 = 20f * (float) Math.log10(lEnv + 1e-9f);
    float lTarget = 1f;
    if (lvl2 > LIMIT_DB) {
      float gDb2 = -(lvl2 - LIMIT_DB) * 0.95f;
      lTarget = (float) Math.pow(10, gDb2 / 20.0);
    }
    lGain += (lTarget - lGain) * (lTarget < lGain ? lAtt : lRel);
    x *= lGain;

    // 7) Son tavan
    if (x > MAX_OUT) x = MAX_OUT;
    else if (x < -MAX_OUT) x = -MAX_OUT;
    return x;
  }

  /** Bant başına 3:1 sıkıştırma, eşik -32 dB */
  private float bandGain(int b, float s) {
    float a = Math.abs(s);
    bEnv[b] += (a - bEnv[b]) * (a > bEnv[b] ? cAtt : cRel);
    float lvl = 20f * (float) Math.log10(bEnv[b] + 1e-9f);
    float t = 1f;
    if (lvl > -32f) t = (float) Math.pow(10, -(lvl + 32f) * (2f / 3f) / 20.0);
    bGain[b] += (t - bGain[b]) * (t < bGain[b] ? cAtt : cRel);
    return bGain[b];
  }

  private static short toShort(float f) {
    if (f > 1f) f = 1f;
    if (f < -1f) f = -1f;
    return (short) (f * 32767f);
  }

  private void emitGate(boolean open) { Listener l = listener; if (l != null) l.onGate(open); }
  private void emitLevel(float v) { Listener l = listener; if (l != null) l.onLevel(v); }
  private void emitInputLevel(float v) { Listener l = listener; if (l != null) l.onInputLevel(v); }
  private void emitError(String m) { Listener l = listener; if (l != null) l.onError(m); }
  private void emitStopped(String r) { Listener l = listener; if (l != null) l.onStopped(r); }
  private void emitLatency(float ms) { Listener l = listener; if (l != null) l.onLatency(ms); }

  /** RBJ biquad filtresi */
  private static class Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    double x1 = 0, x2 = 0, y1 = 0, y2 = 0;

    void highpass(double f, double q) {
      double w = 2 * Math.PI * f / SR, c = Math.cos(w), al = Math.sin(w) / (2 * q);
      double a0 = 1 + al;
      b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = (1 + c) / 2 / a0;
      a1 = -2 * c / a0; a2 = (1 - al) / a0;
    }

    void lowpass(double f, double q) {
      double w = 2 * Math.PI * f / SR, c = Math.cos(w), al = Math.sin(w) / (2 * q);
      double a0 = 1 + al;
      b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = (1 - c) / 2 / a0;
      a1 = -2 * c / a0; a2 = (1 - al) / a0;
    }

    void peaking(double f, double dB, double q) {
      double A = Math.pow(10, dB / 40), w = 2 * Math.PI * f / SR;
      double c = Math.cos(w), al = Math.sin(w) / (2 * q);
      double a0 = 1 + al / A;
      b0 = (1 + al * A) / a0; b1 = -2 * c / a0; b2 = (1 - al * A) / a0;
      a1 = -2 * c / a0; a2 = (1 - al / A) / a0;
    }

    float process(float x) {
      double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
      x2 = x1; x1 = x; y2 = y1; y1 = y;
      return (float) y;
    }
  }
}
