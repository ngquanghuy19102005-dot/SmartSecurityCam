package com.smartiot.securitycam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SecurityCamApp";

    // ===== MQTT CONFIG =====
    private static final String BROKER_URL   = "tcp://broker.hivemq.com:1883";
    private static final String CLIENT_ID    = "SecurityApp_" + System.currentTimeMillis();
    private static final String TOPIC_ALERT  = "security/esp32/alert";
    private static final String TOPIC_CONTROL= "security/esp32/control";

    private MqttAndroidClient mqttClient;

    // ===== CAMERA CONFIG =====
    private String esp32CamIp = "192.168.1.105";

    // ===== UI VIEWS =====
    private EditText etCameraIp;
    private Button btnApplyIp;
    private WebView webViewStream;
    private ImageView ivCapturedPhoto;
    private TextView tvCameraStatus, tvSystemArmedStatus;
    private Switch switchArmSystem, switchFlashLight;
    private Button btnCaptureSnapshot, btnAiAnalyzePhoto;
    private CardView cardMotionAlert;
    private TextView tvMotionAlertDetail;
    private LinearLayout alertHistoryContainer;

    // AI
    private TextView tvAiAnalysisResult;
    private Bitmap lastCapturedBitmap = null;
    private AiSecurityHelper aiSecurityHelper;
    private SecurityNotificationHelper notificationHelper;

    private boolean isArmed = true;
    private boolean isFlashOn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        aiSecurityHelper = new AiSecurityHelper(this);
        notificationHelper = new SecurityNotificationHelper(this);

        setupEvents();
        setupAiCallback();
        connectMQTT();
        startLiveStream();
    }

    private void initViews() {
        etCameraIp = findViewById(R.id.etCameraIp);
        btnApplyIp = findViewById(R.id.btnApplyIp);
        webViewStream = findViewById(R.id.webViewStream);
        ivCapturedPhoto = findViewById(R.id.ivCapturedPhoto);
        tvCameraStatus = findViewById(R.id.tvCameraStatus);
        tvSystemArmedStatus = findViewById(R.id.tvSystemArmedStatus);
        switchArmSystem = findViewById(R.id.switchArmSystem);
        switchFlashLight = findViewById(R.id.switchFlashLight);
        btnCaptureSnapshot = findViewById(R.id.btnCaptureSnapshot);
        btnAiAnalyzePhoto = findViewById(R.id.btnAiAnalyzePhoto);
        cardMotionAlert = findViewById(R.id.cardMotionAlert);
        tvMotionAlertDetail = findViewById(R.id.tvMotionAlertDetail);
        alertHistoryContainer = findViewById(R.id.alertHistoryContainer);
        tvAiAnalysisResult = findViewById(R.id.tvAiAnalysisResult);

        etCameraIp.setText(esp32CamIp);

        WebSettings settings = webViewStream.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
    }

    private void setupEvents() {
        btnApplyIp.setOnClickListener(v -> {
            esp32CamIp = etCameraIp.getText().toString().trim();
            startLiveStream();
            Toast.makeText(this, "Đã cập nhật IP: " + esp32CamIp, Toast.LENGTH_SHORT).show();
        });

        switchArmSystem.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isArmed = isChecked;
            publishMqttCommand(isChecked ? "ARM" : "DISARM");
            tvSystemArmedStatus.setText(isChecked ? "ĐANG CANH GÁC (ARMED)" : "TẠM TẮT BÁO ĐỘNG (DISARMED)");
            tvSystemArmedStatus.setTextColor(isChecked ? 0xFF00E676 : 0xFFFF4444);
        });

        switchFlashLight.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isFlashOn = isChecked;
            publishMqttCommand(isChecked ? "FLASH_ON" : "FLASH_OFF");
        });

        btnCaptureSnapshot.setOnClickListener(v -> captureSnapshotFromCamera());

        btnAiAnalyzePhoto.setOnClickListener(v -> {
            if (lastCapturedBitmap != null) {
                tvAiAnalysisResult.setText("🤖 Gemini AI đang phân tích hình ảnh nhận diện người...");
                tvAiAnalysisResult.setVisibility(View.VISIBLE);
                aiSecurityHelper.analyzeSecurityImage(lastCapturedBitmap);
            } else {
                Toast.makeText(this, "Vui lòng chụp ảnh trước!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startLiveStream() {
        String streamUrl = "http://" + esp32CamIp + "/stream";
        String html = "<html><body style='margin:0;padding:0;background-color:black;display:flex;justify-content:center;align-items:center;height:100%;'>"
                + "<img src='" + streamUrl + "' style='width:100%;height:auto;object-fit:contain;'/>"
                + "</body></html>";
        webViewStream.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        tvCameraStatus.setText("● Livestream: http://" + esp32CamIp + "/stream");
    }

    private void captureSnapshotFromCamera() {
        Toast.makeText(this, "Đang chụp ảnh từ ESP32-CAM...", Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                URL url = new URL("http://" + esp32CamIp + "/capture");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(6000);
                connection.connect();

                InputStream input = connection.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(input);

                if (bitmap != null) {
                    lastCapturedBitmap = bitmap;
                    runOnUiThread(() -> {
                        ivCapturedPhoto.setImageBitmap(bitmap);
                        ivCapturedPhoto.setVisibility(View.VISIBLE);
                        btnAiAnalyzePhoto.setEnabled(true);
                        Toast.makeText(MainActivity.this, "Đã chụp ảnh thành công!", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Lỗi kết nối camera: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ===== MQTT =====
    private void connectMQTT() {
        mqttClient = new MqttAndroidClient(getApplicationContext(), BROKER_URL, CLIENT_ID);
        mqttClient.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {}

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                String payload = new String(message.getPayload());
                runOnUiThread(() -> {
                    if (TOPIC_ALERT.equals(topic)) {
                        handleMotionAlert(payload);
                    }
                });
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {}
        });

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);

        try {
            mqttClient.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    try {
                        mqttClient.subscribe(TOPIC_ALERT, 1);
                    } catch (MqttException e) {
                        Log.e(TAG, "Sub error", e);
                    }
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {}
            });
        } catch (MqttException e) {
            Log.e(TAG, "Connect error", e);
        }
    }

    private void publishMqttCommand(String cmd) {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.publish(TOPIC_CONTROL, new MqttMessage(cmd.getBytes()));
            } catch (MqttException e) {
                Log.e(TAG, "Pub error", e);
            }
        }
    }

    private void handleMotionAlert(String alertJson) {
        cardMotionAlert.setVisibility(View.VISIBLE);
        String time = new SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()).format(new Date());
        tvMotionAlertDetail.setText("Cảm biến PIR phát hiện chuyển động lúc: " + time);

        notificationHelper.showMotionAlertNotification(time);

        TextView tvHistory = new TextView(this);
        tvHistory.setText("⚠️ [CẢNH BÁO] Phát hiện có người lúc: " + time);
        tvHistory.setTextColor(0xFFFF5252);
        tvHistory.setTextSize(12);
        tvHistory.setPadding(0, 4, 0, 4);
        alertHistoryContainer.addView(tvHistory, 0);

        captureSnapshotFromCamera();
    }

    private void setupAiCallback() {
        aiSecurityHelper.setCallback(new AiSecurityHelper.AiSecurityCallback() {
            @Override
            public void onAnalysisComplete(String result) {
                runOnUiThread(() -> {
                    tvAiAnalysisResult.setText(result);
                    tvAiAnalysisResult.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> tvAiAnalysisResult.setText("Lỗi AI: " + error));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
            } catch (MqttException ignored) {}
        }
    }
}
