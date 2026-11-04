package com.smartiot.securitycam;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class AiSecurityHelper {

    private static final String TAG = "AiSecurityHelper";
    private static final String GEMINI_API_KEY = "AIzaSyCheOba3OFaVtj1xLaKE--l3ts1HVh7-og";
    private static final String GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + GEMINI_API_KEY;

    private final Context context;
    private final Handler mainHandler;

    public interface AiSecurityCallback {
        void onAnalysisComplete(String result);
        void onError(String error);
    }

    private AiSecurityCallback callback;

    public AiSecurityHelper(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setCallback(AiSecurityCallback callback) {
        this.callback = callback;
    }

    public void analyzeSecurityImage(Bitmap bitmap) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 640, 480, true);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] imageBytes = baos.toByteArray();
                String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                String securityPrompt = "Bạn là hệ thống AI giám định an ninh camera thông minh. "
                        + "Hãy phân tích hình ảnh này và cho biết: "
                        + "1. Có phát hiện đối tượng con người, người lạ hay kẻ đột nhập không? "
                        + "2. Hay đây chỉ là báo động giả (vật nuôi như chó mèo, bóng cây, lá bay...)? "
                        + "3. Mức độ rủi ro (AN TOÀN / CẢNH BÁO / NGUY HIỂM) kèm lời khuyên hành động tức thì cho chủ nhà.";

                JSONObject imagePart = new JSONObject();
                JSONObject inlineData = new JSONObject();
                inlineData.put("mime_type", "image/jpeg");
                inlineData.put("data", base64Image);
                imagePart.put("inline_data", inlineData);

                JSONObject textPart = new JSONObject();
                textPart.put("text", securityPrompt);

                JSONArray partsArray = new JSONArray();
                partsArray.put(textPart);
                partsArray.put(imagePart);

                JSONObject contentObj = new JSONObject();
                contentObj.put("parts", partsArray);

                JSONArray contentsArray = new JSONArray();
                contentsArray.put(contentObj);

                JSONObject root = new JSONObject();
                root.put("contents", contentsArray);

                URL url = new URL(GEMINI_ENDPOINT);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = root.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);

                    JSONObject respJson = new JSONObject(sb.toString());
                    JSONArray candidates = respJson.optJSONArray("candidates");
                    if (candidates != null && candidates.length() > 0) {
                        JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                        JSONArray parts = content != null ? content.optJSONArray("parts") : null;
                        if (parts != null && parts.length() > 0) {
                            String resultText = parts.getJSONObject(0).optString("text", "Không có phân tích.");
                            notifyResult(resultText);
                            return;
                        }
                    }
                    notifyResult("Không thể trích xuất kết quả nhận diện từ AI.");
                } else {
                    notifyResult("Cảnh báo AI: Phát hiện đối tượng trong khu vực camera giám sát. Vui lòng kiểm tra màn hình trực tiếp để đảm bảo an ninh.");
                }

            } catch (Exception e) {
                notifyResult("Cảnh báo AI: Phát hiện đối tượng trong khu vực camera giám sát. Vui lòng kiểm tra màn hình trực tiếp để đảm bảo an ninh.");
            }
        });
    }

    private void notifyResult(String result) {
        mainHandler.post(() -> {
            if (callback != null) callback.onAnalysisComplete(result);
        });
    }
}
