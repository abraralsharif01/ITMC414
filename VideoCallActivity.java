package com.example.socketchatapp;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import com.example.socketchatapp.databinding.ActivityVideoCallBinding;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoCallActivity extends AppCompatActivity {
    private ActivityVideoCallBinding binding;
    private static final String TAG = "VideoCallActivity";
    private TextureView textureView;
    private ImageView remoteVideoView;
    private ExecutorService executor = Executors.newFixedThreadPool(4);
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private Size previewSize;
    private String cameraId;
    private boolean isFrontCamera = true;
    private boolean isCalling = false;
    private boolean isMuted = false;
    private boolean isSpeakerphoneOn = false;
    private Socket videoSocket, audioSocket;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private String serverIp, roomId, username;
    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_call);

        binding = ActivityVideoCallBinding.inflate(getLayoutInflater());
        textureView = findViewById(R.id.textureView);
        remoteVideoView = findViewById(R.id.remoteVideoView);

        serverIp = getSharedPreferences("app_settings", MODE_PRIVATE).getString("server_ip", "");
        roomId = getSharedPreferences("app_settings", MODE_PRIVATE).getString("room_id", "general");
        username = getSharedPreferences("app_settings", MODE_PRIVATE).getString("username", "User");

        if (checkPermissions()) {
            setupCall();
        } else {
            requestPermissions();
        }

        findViewById(R.id.btnMute).setOnClickListener(v -> toggleMute());
        findViewById(R.id.btnSwitchCamera).setOnClickListener(v -> switchCamera());
        findViewById(R.id.btnEndCall).setOnClickListener(v -> endCall());
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) ==
                        PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.MODIFY_AUDIO_SETTINGS) ==
                        PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.INTERNET,
                        Manifest.permission.MODIFY_AUDIO_SETTINGS
                }, 102);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 102 && grantResults.length > 3 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                grantResults[1] == PackageManager.PERMISSION_GRANTED &&
                grantResults[2] == PackageManager.PERMISSION_GRANTED &&
                grantResults[3] == PackageManager.PERMISSION_GRANTED) {
            setupCall();
        } else {
            Toast.makeText(this, "يجب منح جميع الصلاحيات", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupCall() {
        isCalling = true;
        setupAudioMode();
        startCallTimer();
        connectToServers();
        setupCamera();
        startNetworkMonitoring();
    }

    private void setupAudioMode() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(isSpeakerphoneOn);
            audioManager.setMicrophoneMute(false);

            // ضبط مستوى الصوت لأعلى قيمة
            audioManager.setStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                    0
            );
        }
    }

    private void startCallTimer() {
        handler.postDelayed(new Runnable() {
            long startTime = System.currentTimeMillis();

            @Override
            public void run() {
                if (isCalling) {
                    long elapsedMillis = System.currentTimeMillis() - startTime;
                    updateCallDuration(elapsedMillis);
                    handler.postDelayed(this, 1000);
                }
            }
        }, 1000);
    }

    private void updateCallDuration(long millis) {
        int seconds = (int) (millis / 1000);
        int minutes = seconds / 60;
        seconds = seconds % 60;
        String time = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private void startNetworkMonitoring() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isCalling) {
                    checkNetworkConnection();
                    handler.postDelayed(this, 5000);
                }
            }
        }, 5000);
    }

    private void checkNetworkConnection() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        if (activeNetwork == null || !activeNetwork.isConnected()) {
            runOnUiThread(() -> {
                Toast.makeText(this, "فقدان الاتصال بالإنترنت", Toast.LENGTH_SHORT).show();
                endCall();
            });
        }
    }

    private void connectToServers() {
        executor.execute(() -> {
            try {
                videoSocket = new Socket();
                videoSocket.connect(new InetSocketAddress(serverIp, 12347), 5000);
                DataOutputStream videoDos = new DataOutputStream(videoSocket.getOutputStream());
                videoDos.writeUTF(username + ":" + roomId);
                videoDos.flush();

                audioSocket = new Socket();
                audioSocket.connect(new InetSocketAddress(serverIp, 12346), 5000);
                DataOutputStream audioDos = new DataOutputStream(audioSocket.getOutputStream());
                audioDos.writeUTF(username + ":" + roomId);
                audioDos.flush();

                startVideoStreaming();
                startAudioStreaming();

                runOnUiThread(() ->
                        Toast.makeText(this, "تم الاتصال بنجاح", Toast.LENGTH_SHORT).show());

            } catch (IOException e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "فشل الاتصال بالسيرفر", Toast.LENGTH_SHORT).show();
                    endCall();
                });
            }
        });
    }

    private void startVideoStreaming() {
        executor.execute(this::sendVideoFrames);
        executor.execute(this::receiveVideoFrames);
    }

    private void startAudioStreaming() {
        executor.execute(this::sendAudioData);
        executor.execute(this::receiveAudioData);
    }

    private void sendVideoFrames() {
        try {
            DataOutputStream dos = new DataOutputStream(videoSocket.getOutputStream());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            while (isCalling && !videoSocket.isClosed()) {
                Bitmap frame = textureView.getBitmap();
                if (frame != null) {
                    baos.reset();
                    frame.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                    byte[] frameData = baos.toByteArray();

                    dos.writeInt(frameData.length);
                    dos.write(frameData);
                    dos.flush();
                }
                Thread.sleep(33);
            }
        } catch (Exception e) {
            Log.e(TAG, "Video send error", e);
        }
    }

    private void receiveVideoFrames() {
        try {
            DataInputStream dis = new DataInputStream(videoSocket.getInputStream());
            while (isCalling && !videoSocket.isClosed()) {
                int frameSize = dis.readInt();
                byte[] frameData = new byte[frameSize];
                dis.readFully(frameData);

                Bitmap frame = BitmapFactory.decodeByteArray(frameData, 0, frameSize);
                runOnUiThread(() -> remoteVideoView.setImageBitmap(frame));
            }
        } catch (Exception e) {
            Log.e(TAG, "Video receive error", e);
        }
    }

    private void sendAudioData() {
        try {
            int sampleRate = 44100;
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );

            audioRecord.startRecording();
            byte[] buffer = new byte[bufferSize];
            OutputStream os = audioSocket.getOutputStream();

            while (isCalling && !audioSocket.isClosed()) {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                if (bytesRead > 0 && !isMuted) {
                    os.write(buffer, 0, bytesRead);
                    os.flush();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Audio send error", e);
        }
    }

    private void receiveAudioData() {
        try {
            int sampleRate = 44100;
            int bufferSize = AudioTrack.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            audioTrack = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );

            // ضبط مستوى الصوت لأعلى قيمة
            float maxVolume = AudioTrack.getMaxVolume();
            audioTrack.setVolume(maxVolume);

            // أو يمكنك استخدام قيمة أعلى من القيمة القصوى (قد لا يعمل على جميع الأجهزة)
            // audioTrack.setVolume(maxVolume * 1.5f);

            audioTrack.play();
            InputStream is = audioSocket.getInputStream();
            byte[] buffer = new byte[bufferSize];

            while (isCalling && !audioSocket.isClosed()) {
                int bytesRead = is.read(buffer);
                if (bytesRead > 0) {
                    audioTrack.write(buffer, 0, bytesRead);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Audio receive error", e);
        }
    }

    private void setupCamera() {
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            cameraId = getCameraId(manager);
            previewSize = new Size(640, 480);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED) {
                return;
            }

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createPreviewSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    cleanup();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    cleanup();
                }
            }, null);
        } catch (Exception e) {
            runOnUiThread(() -> {
                Toast.makeText(this, "فشل فتح الكاميرا", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Failed to open camera", e);
            });
            finish();
        }
    }

    private String getCameraId(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (isFrontCamera && facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT)
                return id;
            else if (!isFrontCamera && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK)
                return id;
        }
        return manager.getCameraIdList()[0];
    }

    private void createPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(
                                        captureRequestBuilder.build(), null, null);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Failed to start camera preview", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            runOnUiThread(() ->
                                    Toast.makeText(VideoCallActivity.this, "فشل تهيئة الكاميرا", Toast.LENGTH_SHORT).show());
                        }
                    }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to create camera session", e);
        }
    }

    private void toggleMute() {
        isMuted = !isMuted;
    }

    private void toggleSpeakerphone() {
        isSpeakerphoneOn = !isSpeakerphoneOn;
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(isSpeakerphoneOn);
        }
    }

    private void switchCamera() {
        isFrontCamera = !isFrontCamera;
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        openCamera();
    }

    private void endCall() {
        isCalling = false;
        handler.removeCallbacksAndMessages(null);
        cleanup();
        finish();
    }

    private void cleanup() {
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (videoSocket != null && !videoSocket.isClosed()) {
                videoSocket.close();
            }
            if (audioSocket != null && !audioSocket.isClosed()) {
                audioSocket.close();
            }
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
                audioTrack = null;
            }

            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Exception e) {
            Log.e(TAG, "Cleanup error", e);
        }
        executor.shutdownNow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        endCall();
    }
}