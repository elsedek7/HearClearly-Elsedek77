package com.hearclearly.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_MIC = 100;
    private static final int SAMPLE_RATE = 16000;

    private AudioRecord recorder;
    private AudioTrack player;
    private Thread audioThread;
    private volatile boolean running = false;
    private volatile float gain = 1.0f;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;

    private TextView status;
    private Button startButton;
    private Button stopButton;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildScreen();
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
        }
    }

    private void buildScreen() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(36, 48, 36, 36);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setBackgroundColor(0xFFF4F7FB);

        TextView title = new TextView(this);
        title.setText("اسمع بوضوح");
        title.setTextSize(30);
        title.setTextColor(0xFF102D52);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 20, 0, 8);
        page.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("مساعد تجريبي لتحسين سماع الكلام");
        subtitle.setTextSize(16);
        subtitle.setTextColor(0xFF52657A);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, 28);
        page.addView(subtitle, matchWrap());

        status = new TextView(this);
        status.setText("الحالة: متوقف");
        status.setTextSize(18);
        status.setTextColor(0xFF102D52);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 18, 0, 18);
        page.addView(status, matchWrap());

        startButton = new Button(this);
        startButton.setText("ابدأ الاستماع");
        startButton.setTextSize(18);
        startButton.setOnClickListener(v -> beginOrAskPermission());
        page.addView(startButton, matchWrap());

        stopButton = new Button(this);
        stopButton.setText("إيقاف");
        stopButton.setTextSize(18);
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopListening());
        page.addView(stopButton, matchWrap());

        TextView gainLabel = new TextView(this);
        gainLabel.setText("مستوى تحسين الصوت: 100%");
        gainLabel.setTextSize(16);
        gainLabel.setTextColor(0xFF102D52);
        gainLabel.setPadding(0, 28, 0, 6);
        page.addView(gainLabel, matchWrap());

        SeekBar gainBar = new SeekBar(this);
        gainBar.setMax(80); // 0.6x to 1.4x
        gainBar.setProgress(40);
        gainBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                gain = 0.6f + (progress / 100.0f);
                gainLabel.setText("مستوى تحسين الصوت: " + Math.round(gain * 100) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        page.addView(gainBar, matchWrap());

        TextView safety = new TextView(this);
        safety.setText("تنبيه للسلامة: استخدم سماعة أذن، وابدأ بأقل مستوى صوت. لا تستخدم مكبر الهاتف أثناء التقاط الصوت لتجنب الصفير. هذا إصدار تجريبي وليس بديلًا عن السماعة الطبية أو فحص السمع.");
        safety.setTextSize(14);
        safety.setTextColor(0xFF8A4B18);
        safety.setPadding(0, 28, 0, 0);
        page.addView(safety, matchWrap());

        setContentView(page);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void beginOrAskPermission() {
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
        } else {
            startListening();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MIC && grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            Toast.makeText(this, "يحتاج التطبيق إلى إذن الميكروفون لالتقاط الصوت", Toast.LENGTH_LONG).show();
        }
    }

    private void startListening() {
        if (running) return;

        // A wired or Bluetooth earphone is strongly recommended for the first test.
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        manager.setSpeakerphoneOn(false);

        int minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int minOut = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);

        if (minIn <= 0 || minOut <= 0) {
            status.setText("تعذر تهيئة الصوت على هذا الجهاز");
            manager.setMode(AudioManager.MODE_NORMAL);
            return;
        }

        int bufferSize = Math.max(minIn, minOut) * 2;

        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            player = new AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize, AudioTrack.MODE_STREAM);

            if (recorder.getState() != AudioRecord.STATE_INITIALIZED ||
                    player.getState() != AudioTrack.STATE_INITIALIZED) {
                releaseAudio();
                status.setText("الهاتف لم يسمح ببدء معالجة الصوت");
                manager.setMode(AudioManager.MODE_NORMAL);
                return;
            }

            int sessionId = recorder.getAudioSessionId();
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId);
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId);
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
            }

            recorder.startRecording();
            player.play();
            running = true;
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            status.setText("الحالة: يستمع الآن — استخدم سماعة الأذن");

            audioThread = new Thread(() -> {
                short[] samples = new short[bufferSize / 2];
                while (running) {
                    int count = recorder.read(samples, 0, samples.length);
                    if (count > 0) {
                        float currentGain = gain;
                        for (int i = 0; i < count; i++) {
                            int value = Math.round(samples[i] * currentGain);
                            // Conservative peak limiter to reduce harsh clipping.
                            if (value > 28000) value = 28000;
                            if (value < -28000) value = -28000;
                            samples[i] = (short) value;
                        }
                        player.write(samples, 0, count);
                    }
                }
            }, "HearClearlyAudio");
            audioThread.start();

        } catch (Exception e) {
            stopListening();
            status.setText("حدث خطأ في تشغيل الصوت");
            Toast.makeText(this, "تعذر تشغيل الصوت على هذا الهاتف", Toast.LENGTH_LONG).show();
        }
    }

    private void stopListening() {
        running = false;
        if (audioThread != null) {
            try { audioThread.join(500); } catch (InterruptedException ignored) {}
            audioThread = null;
        }
        releaseAudio();
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        manager.setMode(AudioManager.MODE_NORMAL);
        manager.setSpeakerphoneOn(false);
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        status.setText("الحالة: متوقف");
    }

    private void releaseAudio() {
        try { if (recorder != null) { recorder.stop(); recorder.release(); } } catch (Exception ignored) {}
        try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) {}
        try { if (echoCanceler != null) echoCanceler.release(); } catch (Exception ignored) {}
        try { if (noiseSuppressor != null) noiseSuppressor.release(); } catch (Exception ignored) {}
        recorder = null;
        player = null;
        echoCanceler = null;
        noiseSuppressor = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (running) stopListening();
    }

    @Override
    protected void onDestroy() {
        stopListening();
        super.onDestroy();
    }
}
