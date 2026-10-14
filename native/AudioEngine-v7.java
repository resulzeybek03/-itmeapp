package com.hdklinik.isitme;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

import org.json.JSONObject;

/**
 * Ses motoru: mikrofon -> sessizlik kapısı -> filtreler -> kompresör -> kazanç -> sınırlayıcı -> kulaklık.
 * WebView'dan bağımsız çalışır; uygulama arka plana atılsa da foreground servis içinde devam eder.
 */
public class AudioEngine {

  public interface Listener {
    void onGate(boolean open);
    void onLevel(float level);
    void onError(String message);
  }

  private static final AudioEngine INSTANCE = new AudioEngine();
  public static AudioEngine get() { return INSTANCE; }

  private static final int SR = 48000;
  private static final int BLOCK = 256;
  private static final float LIMIT_DB = -6f;   // Güvenlik sınırı (dBFS)
  private static final float MAX_OUT = 0.5f;   // Son güvenlik tavanı

  private volatile Listener listener;
  private volatile boolean running = false;
  private volatile boolean filtersDirty = true;
  private Thread thread;

  // Ayarlar (JS'den gelir)
  private volatile float gateDb = -42f;
  private volatile boolean muteFully = true;
  private volatile float gain = 3f;
  private volatile float midDb = 8f;
  private volatile float trebleDb = 10f;
  private volatile float cutHz = 180f;
  private volatile float pan = 0f;
  private volatile float far = 0.5f;   // Uzak ses yükseltme (0-1)

  // DSP durumu (yalnızca ses thread'i kullanır)
  private float thOpen, thClose, floorG;
  private float env = 0f, gateG = 0f;
  private boolean gateOpen = false;
  private int hold = 0;
  private final int holdN = (int) (0.35 * SR);
  private float cEnv = 0f, cGain = 1f;
  private float uGain = 1f;
  private float lEnv = 0f, lGain = 1f;
  private final Biquad hp = new Biquad(), mud = new Biquad(), mid = new Biquad(), treble = new Biquad();

  private final float eAtt = coef(0.002f), eRel = coef(0.12f);
  private final float gAtt = coef(0.002f), gRel = coef(0.25f);
  private final float uAtt = coef(0.05f), uRel = coef(0.3f);
  private final float cAtt = coef(0.004f), cRel = coef(0.15f);
  private final float lAtt = coef(0.001f), lRel = coef(0.10f);

  private static float coef(float seconds) {
    return 1f - (float) Math.exp(-1.0 / (seconds * SR));
  }

  public void setListener(Listener l) { listener = l; }

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

    AudioRecord rec;
    AudioTrack track;
    try {
      rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
          AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(inMin, BLOCK * 8) * 2);
      track = new AudioTrack(
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build(),
          new AudioFormat.Builder()
              .setSampleRate(SR)
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
              .build(),
          Math.max(outMin, BLOCK * 8) * 2,
          AudioTrack.MODE_STREAM,
          AudioManager.AUDIO_SESSION_ID_GENERATE);
    } catch (Exception e) {
      emitError("Ses motoru oluşturulamadı: " + e.getMessage());
      return false;
    }

    if (rec.getState() != AudioRecord.STATE_INITIALIZED || track.getState() != AudioTrack.STATE_INITIALIZED) {
      rec.release();
      track.release();
      emitError("Mikrofon başlatılamadı (izin verilmiş mi?)");
      return false;
    }

    env = 0f; gateG = 0f; gateOpen = false; hold = 0;
    cEnv = 0f; cGain = 1f; uGain = 1f; lEnv = 0f; lGain = 1f;
    filtersDirty = true;
    running = true;

    rec.startRecording();
    track.play();

    thread = new Thread(() -> loop(rec, track), "ses-motoru");
    thread.setPriority(Thread.MAX_PRIORITY);
    thread.start();
    return true;
  }

  public void stop() {
    running = false;
    Thread t = thread;
    thread = null;
    if (t != null && t != Thread.currentThread()) {
      try { t.join(1500); } catch (InterruptedException ignored) {}
    }
  }

  private void loop(AudioRecord rec, AudioTrack track) {
    short[] in = new short[BLOCK];
    short[] out = new short[BLOCK * 2];
    long lastLevel = 0;
    float peak = 0f;
    try {
      rec.startRecording();
      while (running) {
        int n = rec.read(in, 0, BLOCK);
        if (n < 0) { emitError("Mikrofon okuma hatası: " + n); break; }
        if (n == 0) continue;
        if (filtersDirty) { computeParams(); filtersDirty = false; }

        float bl = pan > 0 ? 1f - pan : 1f;
        float br = pan < 0 ? 1f + pan : 1f;
        for (int i = 0; i < n; i++) {
          float x = processSample(in[i] / 32768f);
          float a = Math.abs(x);
          if (a > peak) peak = a;
          out[2 * i] = toShort(x * bl);
          out[2 * i + 1] = toShort(x * br);
        }
        track.write(out, 0, n * 2);

        long now = System.currentTimeMillis();
        if (now - lastLevel > 100) {
          emitLevel(peak);
          peak = 0f;
          lastLevel = now;
        }
      }
    } finally {
      running = false;
      try { rec.stop(); } catch (Exception ignored) {}
      try { track.stop(); } catch (Exception ignored) {}
      rec.release();
      track.release();
    }
  }

  private void computeParams() {
    hp.highpass(Math.max(40, cutHz), 0.707);
    mud.peaking(320, cutHz > 150 ? -6 : -2, 1.1);
    mid.peaking(2400, midDb, 0.9);
    treble.peaking(4500, trebleDb, 0.8);
    thOpen = (float) Math.pow(10, gateDb / 20.0);
    thClose = (float) Math.pow(10, (gateDb - 10) / 20.0);
    floorG = muteFully ? 0f : 0.01f;
  }

  private float processSample(float x) {
    // 1) Sessizlik kapısı (histerezis + tutma süresi + yumuşak geçiş)
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

    // 3) Kompresör (-32 dB eşik, 4:1)
    a = Math.abs(x);
    cEnv += (a - cEnv) * (a > cEnv ? cAtt : cRel);
    float lvl = 20f * (float) Math.log10(cEnv + 1e-9f);
    float cTarget = 1f;
    if (lvl > -32f) {
      float gDb = -(lvl + 32f) * 0.75f;
      cTarget = (float) Math.pow(10, gDb / 20.0);
    }
    cGain += (cTarget - cGain) * (cTarget < cGain ? cAtt : cRel);
    x *= cGain;

    // Uzak / düşük ses yükseltme: konuşma açıkken kısık sesleri yukarı çeker
    float uDb = 0f;
    if (far > 0f && gateOpen && lvl < -32f) uDb = Math.min(12f, (-32f - lvl) * far);
    float uTarget = (float) Math.pow(10, uDb / 20.0);
    uGain += (uTarget - uGain) * (uTarget > uGain ? uAtt : uRel);
    x *= uGain;

    // 4) Kazanç
    x *= gain;

    // 5) Sınırlayıcı
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

    // 6) Son tavan
    if (x > MAX_OUT) x = MAX_OUT;
    else if (x < -MAX_OUT) x = -MAX_OUT;
    return x;
  }

  private static short toShort(float f) {
    if (f > 1f) f = 1f;
    if (f < -1f) f = -1f;
    return (short) (f * 32767f);
  }

  private void emitGate(boolean open) {
    Listener l = listener;
    if (l != null) l.onGate(open);
  }

  private void emitLevel(float level) {
    Listener l = listener;
    if (l != null) l.onLevel(level);
  }

  private void emitError(String msg) {
    Listener l = listener;
    if (l != null) l.onError(msg);
  }

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
