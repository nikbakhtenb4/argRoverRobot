#include <Arduino.h>

// ---------------- اپتوها ----------------
// کانال 0 و 1: 2.6V با DAC | کانال 2 و 3: 2.8V با Hi-Z / زمین
const uint8_t PINS[4]     = {25, 26, 27, 13};
const uint8_t ADC_PINS[4] = {34, 35, 32, 33};
const bool    IS_DAC[4]   = {true, true, false, false};

const uint32_t VDD_MV = 3300;
const uint32_t MAX_MV = 3000;

uint32_t targetMv[4] = {2630, 2630, 2800, 2800};
bool     enabled[4]  = {false, false, false, false};

// ---------------- سروو DS3002 ----------------
const uint8_t  SERVO_PINS[2]   = {18, 19};
const uint8_t SERVO_CH[2] = {0, 1};   // کانال‌های LEDC
const bool     SERVO_INVERT[2] = {false, false};
const float    RANGE_DEG       = 180.0f;     // زاویه کل سروو (از دیتاشیت چک کن)
const uint16_t SERVO_MIN_US    = 500;        // از دیتاشیت چک کن
const uint16_t SERVO_MAX_US    = 2500;
const float    HOME_DEG[2]     = {90, 90};
const float    LIMIT_MIN       = 60;         // حد ایمنی مکانیکی
const float    LIMIT_MAX       = 120;
const uint32_t HOME_SETTLE_MS = 1500;   // زمان رسیدن فیزیکی سروو‌ها به home
const uint32_t OPTO_DELAY_MS  = 2000;   // تأخیر بعد از home تا فعال شدن اپتوها


float curDeg[2] = {90, 90};
float velDps[2] = {0, 0};
float tgtDeg[2] = {90, 90};
float smoothTime = 0.5f;     // ثانیه: بزرگ‌تر = نرم‌تر
float maxSpeed   = 30.0f;    // درجه بر ثانیه
bool  homed      = false;
bool  pendingArm = false;

// ===================== اپتو =====================
uint8_t mvToDac(uint32_t mv) {
  if (mv > VDD_MV) mv = VDD_MV;
  return (uint8_t)((mv * 255UL + VDD_MV / 2) / VDD_MV);
}

void applyChannel(uint8_t ch) {
  uint8_t pin = PINS[ch];
  if (IS_DAC[ch]) {
    dacWrite(pin, enabled[ch] ? mvToDac(targetMv[ch]) : 0);
  } else {
    if (enabled[ch]) {
      pinMode(pin, INPUT);        // Hi-Z
    } else {
      digitalWrite(pin, LOW);
      pinMode(pin, OUTPUT);       // کشیدن به زمین
    }
  }
}

void printState(uint8_t ch) {
  if (IS_DAC[ch])
    Serial.printf("CH%d: %s | %u mV (DAC)\n", ch + 1, enabled[ch] ? "ON" : "OFF", targetMv[ch]);
  else
    Serial.printf("CH%d: %s | %s\n", ch + 1, enabled[ch] ? "ON" : "OFF",
                  enabled[ch] ? "Hi-Z (2.8V from board)" : "pulled LOW");
}

void disableAllOptos() {
  pendingArm = false;
  for (int i = 0; i < 4; i++) { enabled[i] = false; applyChannel(i); }
}

// ===================== سروو =====================
void servoWrite(uint8_t i, float deg) {
  float a = SERVO_INVERT[i] ? (RANGE_DEG - deg) : deg;
  float us = SERVO_MIN_US + (a / RANGE_DEG) * (SERVO_MAX_US - SERVO_MIN_US);
  uint32_t duty = (uint32_t)(us / 20000.0f * 65535.0f + 0.5f);   // 50Hz, 16 بیت
  ledcWrite(SERVO_CH[i], duty);
}

float clampDeg(float d) {
  if (d < LIMIT_MIN) return LIMIT_MIN;
  if (d > LIMIT_MAX) return LIMIT_MAX;
  return d;
}

// فیلتر میرای بحرانی (SmoothDamp): سرعت همیشه پیوسته است
float smoothDamp(float cur, float tgt, float &vel, float st, float vmax, float dt) {
  if (st < 0.05f) st = 0.05f;
  float omega  = 2.0f / st;
  float x      = omega * dt;
  float e      = 1.0f / (1.0f + x + 0.48f * x * x + 0.235f * x * x * x);
  float change = cur - tgt;
  float maxChg = vmax * st;
  if (change >  maxChg) change =  maxChg;
  if (change < -maxChg) change = -maxChg;
  float t2   = cur - change;
  float temp = (vel + omega * change) * dt;
  vel = (vel - omega * temp) * e;
  float out = t2 + (change + temp) * e;
  if ((tgt - cur > 0) == (out > tgt)) { out = tgt; vel = 0; }
  return out;
}

bool atHome() {
  for (int i = 0; i < 2; i++)
    if (fabsf(curDeg[i] - HOME_DEG[i]) > 0.1f || fabsf(velDps[i]) > 0.5f) return false;
  return true;
}

void goHome() {
  for (int i = 0; i < 2; i++) tgtDeg[i] = HOME_DEG[i];
}

void updateServos() {
  static uint32_t lastUs = micros();
  uint32_t nowUs = micros();
  float dt = (nowUs - lastUs) / 1e6f;
  if (dt < 0.005f) return;            // به‌روزرسانی هر 5ms یا بیشتر
  lastUs = nowUs;
  if (dt > 0.05f) dt = 0.05f;         // محافظت در برابر وقفه‌های طولانی

  for (int i = 0; i < 2; i++) {
    curDeg[i] = smoothDamp(curDeg[i], tgtDeg[i], velDps[i], smoothTime, maxSpeed, dt);
    servoWrite(i, curDeg[i]);
  }

  if (pendingArm && atHome()) {
    pendingArm = false;
    for (int i = 0; i < 4; i++) { enabled[i] = true; applyChannel(i); }
    Serial.println("ARMED: servos at home, optos ON");
  }
}

// ===================== دستورها =====================
int splitTokens(const String &cmd, String *tokens, int maxN) {
  int n = 0, start = 0;
  while (start < (int)cmd.length() && n < maxN) {
    int sp = cmd.indexOf(' ', start);
    if (sp < 0) sp = cmd.length();
    String t = cmd.substring(start, sp);
    t.trim();
    if (t.length() > 0) tokens[n++] = t;
    start = sp + 1;
  }
  return n;
}

void printStatus() {
  Serial.printf("S1=%.2f(tgt %.2f) S2=%.2f(tgt %.2f) | ", curDeg[0], tgtDeg[0], curDeg[1], tgtDeg[1]);
  for (int i = 0; i < 4; i++) {
    uint32_t sum = 0;
    for (int k = 0; k < 8; k++) sum += analogReadMilliVolts(ADC_PINS[i]);
    Serial.printf("CH%d %s %4u mV | ", i + 1, enabled[i] ? "ON " : "OFF", sum / 8);
  }
  Serial.println();
}

void handleOptoCommand(String *tokens, int n) {
  if (n < 2) { Serial.println("Format: <ch...> <on|off|mV>  e.g. '1 2 on'"); return; }
  String arg = tokens[n - 1];

  bool selected[4] = {false, false, false, false};
  for (int i = 0; i < n - 1; i++) {
    int ch = tokens[i].toInt();
    if (ch < 1 || ch > 4) {
      Serial.printf("Invalid channel '%s' (must be 1..4)\n", tokens[i].c_str());
      return;
    }
    selected[ch - 1] = true;
  }

  bool isOn = (arg == "on"), isOff = (arg == "off");
  long mv = 0;
  if (!isOn && !isOff) {
    mv = arg.toInt();
    if (mv <= 0 && arg != "0") { Serial.println("Last word must be on, off or mV"); return; }
    if (mv < 0) mv = 0;
    if (mv > (long)MAX_MV) mv = MAX_MV;
  }

  if (isOn && !homed) { Serial.println("REFUSED: servos not homed yet"); return; }

  for (int ch = 0; ch < 4; ch++) {
    if (!selected[ch]) continue;
    if (isOn) enabled[ch] = true;
    else if (isOff) enabled[ch] = false;
    else {
      if (!IS_DAC[ch]) { Serial.printf("CH%d has no adjustable voltage\n", ch + 1); continue; }
      targetMv[ch] = mv;
    }
    applyChannel(ch);
    printState(ch);
  }
}

void handleCommand(String cmd) {
  cmd.trim();
  cmd.toLowerCase();
  if (cmd.length() == 0) return;

  String t[8];
  int n = splitTokens(cmd, t, 8);
  if (n == 0) return;

  if (cmd == "all off" || cmd == "disarm" || cmd == "stop") {
    disableAllOptos();
    Serial.println("OPTOS OFF (servos hold position)");
    return;
  }
  if (cmd == "all on") {
    if (!homed) { Serial.println("REFUSED: servos not homed yet"); return; }
    for (int i = 0; i < 4; i++) { enabled[i] = true; applyChannel(i); }
    Serial.println("ALL ON");
    return;
  }
  if (cmd == "arm") {
    goHome();
    pendingArm = true;
    Serial.println("Moving servos to home, optos will turn on when reached...");
    return;
  }
  if (cmd == "home") { goHome(); Serial.println("Servos -> home"); return; }
  if (cmd == "status" || cmd == "s" || cmd == "?") { printStatus(); return; }

  if (t[0] == "smooth" && n == 2) {
    float s = t[1].toFloat();
    if (s < 0.05f) s = 0.05f;
    if (s > 5.0f)  s = 5.0f;
    smoothTime = s;
    Serial.printf("Smooth time: %.2f s\n", smoothTime);
    return;
  }
  if (t[0] == "speed" && n == 2) {
    float s = t[1].toFloat();
    if (s < 1) s = 1;
    if (s > 200) s = 200;
    maxSpeed = s;
    Serial.printf("Max speed: %.0f deg/s\n", maxSpeed);
    return;
  }
  if ((t[0] == "s1" || t[0] == "s2") && n == 2) {
    int i = (t[0] == "s1") ? 0 : 1;
    tgtDeg[i] = clampDeg(t[1].toFloat());
    Serial.printf("Servo%d target: %.2f\n", i + 1, tgtDeg[i]);
    return;
  }
  if ((t[0] == "j1" || t[0] == "j2") && n == 2) {
    int i = (t[0] == "j1") ? 0 : 1;
    tgtDeg[i] = clampDeg(tgtDeg[i] + t[1].toFloat());
    Serial.printf("Servo%d target: %.2f\n", i + 1, tgtDeg[i]);
    return;
  }
  if (t[0] == "s" && n == 3) {
    tgtDeg[0] = clampDeg(t[1].toFloat());
    tgtDeg[1] = clampDeg(t[2].toFloat());
    Serial.printf("Servo targets: %.2f , %.2f\n", tgtDeg[0], tgtDeg[1]);
    return;
  }

  handleOptoCommand(t, n);
}

// ===================== setup / loop =====================
void setup() {
  // مرحله ۱: قبل از هر کاری، همه اپتوها غیرفعال (کانال 3 و 4 به زمین کشیده می‌شوند)
  disableAllOptos();

  Serial.begin(115200);
  Serial.setTimeout(30);
  delay(300);

  analogReadResolution(12);
  for (int i = 0; i < 4; i++) analogSetPinAttenuation(ADC_PINS[i], ADC_11db);

  // مرحله ۲: سروو‌ها به home
  for (int i = 0; i < 2; i++) {
    ledcSetup(SERVO_CH[i], 50, 16);
    ledcAttachPin(SERVO_PINS[i], SERVO_CH[i]);
    curDeg[i] = HOME_DEG[i];
    tgtDeg[i] = HOME_DEG[i];
    servoWrite(i, curDeg[i]);
  }
  Serial.println("Optos OFF. Servos moving to HOME...");
  delay(HOME_SETTLE_MS);
  homed = true;
  Serial.println("Servos at HOME.");

  // مرحله ۳: ۲ ثانیه صبر، بعد فعال کردن اپتوها
  Serial.printf("Optos will turn ON in %u ms (send 'stop' to abort after)\n", OPTO_DELAY_MS);
  delay(OPTO_DELAY_MS);
  for (int i = 0; i < 4; i++) { enabled[i] = true; applyChannel(i); }
  Serial.println("ARMED: optos ON");

  Serial.println("Commands: arm | disarm | home | status | s1 95 | s2 85 | s 95 85 | j1 0.2 | smooth 0.6 | speed 20");
  Serial.println("          '1 2 on' | '3 off' | '1 2 2650' | 'all off'");
}

void loop() {
  if (Serial.available()) handleCommand(Serial.readStringUntil('\n'));
  updateServos();
}