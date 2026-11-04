/***********************************************************************************
 * HỆ THỐNG CAMERA AN NINH GIÁM SÁT CHUYỂN ĐỘNG - ESP32-CAM AI-THINKER
 * Nâng cấp chuẩn công nghiệp:
 *  - Giữ nguyên thông tin Telegram Bot gốc để gửi ảnh cảnh báo khi có người
 *  - Tích hợp HTTP MJPEG Video Streaming (/stream) & Snapshot Capture (/capture)
 *  - Tích hợp MQTT kết nối App Android điều khiển Bật/Tắt báo động (Arm/Disarm) & Đèn Flash
 *  - Điều khiển hiển thị LCD 1602 I2C (SDA: 14, SCL: 15)
 *  - Cảm biến PIR: Chân 13 | Đèn Flash: Chân 4
 ***********************************************************************************/

#include "esp_camera.h"
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include "esp_http_server.h"

// ===== CẤU HÌNH WIFI =====
const char* ssid = "Huy";
const char* password = "1234567899";

// ===== TELEGRAM BOT (GIỮ NGUYÊN TỪ DỰ ÁN GỐC) =====
const char* BOTtoken = "7398458141:AAGP42NWGsCmve_eZgZQyemwEtZ3O_2_AII";
const char* CHAT_ID = "6930289364";
const char* telegram_api = "api.telegram.org";

// ===== MQTT BROKER =====
const char* mqtt_server = "broker.hivemq.com";
const int mqtt_port = 1883;
#define TOPIC_MOTION_ALERT "security/esp32/alert"
#define TOPIC_CONTROL      "security/esp32/control"
#define TOPIC_STATUS       "security/esp32/status"

// ===== PHẦN CỨNG CHÂN =====
#define PIR_PIN    13
#define FLASH_PIN  4
#define I2C_SDA    14
#define I2C_SCL    15

LiquidCrystal_I2C lcd(0x27, 16, 2);
WiFiClientSecure secClient;
WiFiClient espMqttClient;
PubSubClient mqttClient(espMqttClient);
httpd_handle_t stream_httpd = NULL;

// ===== BIẾN TRẠNG THÁI =====
bool isSystemArmed = true;     // Chế độ canh gác (ARM: Bật báo động)
bool flashState = false;       // Trạng thái đèn Flash
bool lastPirState = false;
unsigned long lastTelegramSendTime = 0;
const unsigned long telegramCooldown = 15000; // Giới hạn gửi ảnh 15s/lần chống spam

// Cấu hình Camera AI-Thinker OV2640 (Chuẩn ESP32-CAM)
camera_config_t cameraConfig = {
  .pin_pwdn       = 32,
  .pin_reset      = -1,
  .pin_xclk       = 0,
  .pin_sccb_sda   = 26,
  .pin_sccb_scl   = 27,
  .pin_d7         = 35,
  .pin_d6         = 34,
  .pin_d5         = 39,
  .pin_d4         = 36,
  .pin_d3         = 21,
  .pin_d2         = 19,
  .pin_d1         = 18,
  .pin_d0         = 5,
  .pin_vsync      = 25,
  .pin_href       = 23,
  .pin_pclk       = 22,
  .xclk_freq_hz   = 20000000,
  .ledc_timer     = LEDC_TIMER_0,
  .ledc_channel   = LEDC_CHANNEL_0,
  .pixel_format   = PIXFORMAT_JPEG,
  .frame_size     = FRAMESIZE_VGA, // 640x480
  .jpeg_quality   = 12,
  .fb_count       = 2,
  .grab_mode      = CAMERA_GRAB_LATEST
};

// Khởi tạo Camera
bool initCamera() {
  esp_err_t err = esp_camera_init(&cameraConfig);
  if (err != ESP_OK) {
    Serial.printf("Khoi tao Camera loi 0x%x\n", err);
    return false;
  }
  return true;
}

// Gửi tin nhắn text lên Telegram
void sendTelegramMessage(const char* message) {
  if (!secClient.connect(telegram_api, 443)) return;
  String url = "/bot" + String(BOTtoken) + "/sendMessage?chat_id=" + String(CHAT_ID) + "&text=" + String(message);
  url.replace(" ", "%20");
  secClient.print(String("GET ") + url + " HTTP/1.1\r\n" +
                  "Host: " + telegram_api + "\r\n" +
                  "User-Agent: ESP32-CAM\r\n" +
                  "Connection: close\r\n\r\n");
  delay(300);
  while (secClient.available()) secClient.readStringUntil('\n');
  secClient.stop();
}

// Gửi ảnh đính kèm lên Telegram
void sendPhotoToTelegram(camera_fb_t *fb) {
  if (!secClient.connect(telegram_api, 443)) return;
  String boundary = "----ESP32CAMSecurity";
  String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n" + String(CHAT_ID) + "\r\n";
  head += "--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"intruder.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n";
  String tail = "\r\n--" + boundary + "--\r\n";

  secClient.print(String("POST /bot") + BOTtoken + "/sendPhoto HTTP/1.1\r\n" +
                  "Host: " + telegram_api + "\r\n" +
                  "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n" +
                  "Content-Length: " + (head.length() + fb->len + tail.length()) + "\r\n" +
                  "Connection: close\r\n\r\n");
  secClient.print(head);
  secClient.write(fb->buf, fb->len);
  secClient.print(tail);

  delay(500);
  while (secClient.available()) secClient.readStringUntil('\n');
  secClient.stop();
}

// ===== HTTP LIVESTREAM & CAPTURE HANDLERS =====
#define PART_BOUNDARY "123456789000000000000987654321"
static const char* _STREAM_CONTENT_TYPE = "multipart/x-mixed-replace;boundary=" PART_BOUNDARY;
static const char* _STREAM_BOUNDARY = "\r\n--" PART_BOUNDARY "\r\n";
static const char* _STREAM_PART = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

esp_err_t stream_handler(httpd_req_t *req) {
  camera_fb_t * fb = NULL;
  esp_err_t res = ESP_OK;
  size_t _jpg_buf_len = 0;
  uint8_t * _jpg_buf = NULL;
  char * part_buf[64];

  res = httpd_resp_set_type(req, _STREAM_CONTENT_TYPE);
  if (res != ESP_OK) return res;

  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");

  while (true) {
    fb = esp_camera_fb_get();
    if (!fb) {
      res = ESP_FAIL;
    } else {
      _jpg_buf_len = fb->len;
      _jpg_buf = fb->buf;
    }
    if (res == ESP_OK) {
      size_t hlen = snprintf((char *)part_buf, 64, _STREAM_PART, _jpg_buf_len);
      res = httpd_resp_send_chunk(req, (const char *)part_buf, hlen);
    }
    if (res == ESP_OK) {
      res = httpd_resp_send_chunk(req, (const char *)_jpg_buf, _jpg_buf_len);
    }
    if (res == ESP_OK) {
      res = httpd_resp_send_chunk(req, _STREAM_BOUNDARY, strlen(_STREAM_BOUNDARY));
    }
    if (fb) {
      esp_camera_fb_return(fb);
      fb = NULL;
      _jpg_buf = NULL;
    } else if (res != ESP_OK) {
      break;
    }
  }
  return res;
}

esp_err_t capture_handler(httpd_req_t *req) {
  camera_fb_t * fb = esp_camera_fb_get();
  if (!fb) {
    httpd_resp_send_500(req);
    return ESP_FAIL;
  }
  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_set_hdr(req, "Content-Disposition", "inline; filename=capture.jpg");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  esp_err_t res = httpd_resp_send(req, (const char *)fb->buf, fb->len);
  esp_camera_fb_return(fb);
  return res;
}

esp_err_t status_handler(httpd_req_t *req) {
  char json[128];
  snprintf(json, sizeof(json),
    "{\"armed\":%s,\"motion\":%s,\"flash\":%s,\"ip\":\"%s\"}",
    isSystemArmed ? "true" : "false",
    (digitalRead(PIR_PIN) == HIGH) ? "true" : "false",
    flashState ? "true" : "false",
    WiFi.localIP().toString().c_str()
  );
  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  return httpd_resp_send(req, json, strlen(json));
}

void startCameraServer() {
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 80;

  httpd_uri_t stream_uri = {
    .uri       = "/stream",
    .method    = HTTP_GET,
    .handler   = stream_handler,
    .user_ctx  = NULL
  };
  httpd_uri_t capture_uri = {
    .uri       = "/capture",
    .method    = HTTP_GET,
    .handler   = capture_handler,
    .user_ctx  = NULL
  };
  httpd_uri_t status_uri = {
    .uri       = "/status",
    .method    = HTTP_GET,
    .handler   = status_handler,
    .user_ctx  = NULL
  };

  if (httpd_start(&stream_httpd, &config) == ESP_OK) {
    httpd_register_uri_handler(stream_httpd, &stream_uri);
    httpd_register_uri_handler(stream_httpd, &capture_uri);
    httpd_register_uri_handler(stream_httpd, &status_uri);
    Serial.println("Web Stream Server da san sang tren cong 80!");
  }
}

// ===== MQTT CALLBACK =====
void mqttCallback(char* topic, byte* payload, unsigned int length) {
  String msg = "";
  for (unsigned int i = 0; i < length; i++) msg += (char)payload[i];
  msg.trim();

  Serial.println("MQTT Lệnh nhận: " + msg);
  if (msg == "ARM") {
    isSystemArmed = true;
    Serial.println("He thong: DA BAT BAO DONG (ARMED)");
  } else if (msg == "DISARM") {
    isSystemArmed = false;
    Serial.println("He thong: DA TAT BAO DONG (DISARMED)");
  } else if (msg == "FLASH_ON") {
    flashState = true;
    digitalWrite(FLASH_PIN, HIGH);
  } else if (msg == "FLASH_OFF") {
    flashState = false;
    digitalWrite(FLASH_PIN, LOW);
  }
}

void reconnectMQTT() {
  if (!mqttClient.connected()) {
    if (mqttClient.connect("ESP32_Security_CAM", TOPIC_STATUS, 1, true, "offline")) {
      mqttClient.publish(TOPIC_STATUS, "online", true);
      mqttClient.subscribe(TOPIC_CONTROL);
      Serial.println("MQTT An ninh da ket noi!");
    }
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(PIR_PIN, INPUT);
  pinMode(FLASH_PIN, OUTPUT);
  digitalWrite(FLASH_PIN, LOW);

  Wire.begin(I2C_SDA, I2C_SCL);
  lcd.init();
  lcd.backlight();
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("He thong An ninh");
  lcd.setCursor(0, 1);
  lcd.print("Khoi dong...");

  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nWiFi OK! IP: " + WiFi.localIP().toString());

  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("WiFi Ket noi OK!");
  lcd.setCursor(0, 1);
  lcd.print(WiFi.localIP().toString());
  delay(1500);

  secClient.setInsecure(); // Bỏ qua SSL cho Telegram

  if (!initCamera()) {
    Serial.println("Loi khoi tao Camera!");
    lcd.clear();
    lcd.setCursor(0, 0);
    lcd.print("Camera Error!");
    while (true) delay(1000);
  }

  startCameraServer();

  mqttClient.setServer(mqtt_server, mqtt_port);
  mqttClient.setCallback(mqttCallback);

  sendTelegramMessage("He thong Camera An Ninh ESP32-CAM da san sang hoat dong!");

  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("CANH BAO: SAN SANG");
  lcd.setCursor(0, 1);
  lcd.print("IP: " + WiFi.localIP().toString());
}

void loop() {
  if (!mqttClient.connected()) {
    reconnectMQTT();
  } else {
    mqttClient.loop();
  }

  int pirState = digitalRead(PIR_PIN);

  if (pirState == HIGH && isSystemArmed) {
    unsigned long now = millis();
    if (now - lastTelegramSendTime > telegramCooldown) {
      Serial.println("Phat hien chuyen dong! Kich hoat chup anh...");

      lcd.clear();
      lcd.setCursor(0, 0);
      lcd.print("PHAT HIEN NGUOI!");
      lcd.setCursor(0, 1);
      lcd.print("Dang gui anh...");

      digitalWrite(FLASH_PIN, HIGH);
      delay(80);

      camera_fb_t *fb = esp_camera_fb_get();
      if (fb) {
        // Gửi Telegram
        sendTelegramMessage("⚠️ CẢNH BÁO AN NINH: Phát hiện chuyển động đột nhập! Hình ảnh ghi lại:");
        sendPhotoToTelegram(fb);
        esp_camera_fb_return(fb);

        // Gửi MQTT Alert lên App Android
        char alertMsg[128];
        snprintf(alertMsg, sizeof(alertMsg), "{\"event\":\"motion\",\"timestamp\":%lu,\"action\":\"photo_sent\"}", now);
        mqttClient.publish(TOPIC_MOTION_ALERT, alertMsg);
      }

      digitalWrite(FLASH_PIN, flashState ? HIGH : LOW);
      lastTelegramSendTime = now;

      lcd.clear();
      lcd.setCursor(0, 0);
      lcd.print("Da gui canh bao!");
      lcd.setCursor(0, 1);
      lcd.print("Trang thai: ARMED");
      delay(1500);
    }
  }

  delay(200);
}
