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

// ---------------- سروو ----------------
const uint8_t SERVO_PINS[2]   = {18, 19};
const bool    SERVO_INVERT[2] = {false, false};   // اگر جهت برعکس بود true کن
const float   HOME_DEG[2]     = {90, 90};         // زاویه‌ی تراز هر سروو
const float   LIMIT_MIN       = 60;               // حد ایمنی مکانیکی (درجه)
const float   LIMIT_MAX       = 120;
const uint16_t SERVO_MIN_US   = 500;
const uint16_t SERVO_MAX_US   = 2400;

float curDeg[2] = {90, 90};
float tgtDeg[2] = {90, 90};
float speedDps  = 40;           // سرعت حرکت (درجه بر ثانیه)
bool  homed     = false;        // سروو‌ها حداقل یک بار به home رسیده‌اند
bool  pendingArm = false;       // منتظر رسیدن به home برای فعال کردن اپتوها

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
  float a = SERVO_INVERT[i] ? (180.0f - deg) : deg;
  float us = SERVO_MIN_US + (a / 180.0f) * (SERVO_MAX_US - SERVO_MIN_US);
  uint32_t duty = (uint32_t)(us / 20000.0f * 65535.0f);   // 50Hz, 16 بیت
  ledcWrite(SERVO_PINS[i], duty);
}

float clampDeg(float d) {
  if (d < LIMIT_MIN) return LIMIT_MIN;
  if (d > LIMIT_MAX) return LIMIT_MAX;
  return d;
}

bool atHome() {
  for (int i = 0; i < 2; i++)
    if (fabsf(curDeg[i] - HOME_DEG[i]) > 0.5f) return false;
  return true;
}

void goHome() {
  for (int i = 0; i < 2; i++) tgtDeg[i] = HOME_DEG[i];
}

// حرکت نرم و بدون بلوکه شدن به سمت هدف
void updateServos() {
  static uint32_t last = millis();
  uint32_t now = millis();
  float dt = (now - last) / 1000.0f;
  last = now;
  float step = speedDps * dt;

  for (int i = 0; i < 2; i++) {
    float diff = tgtDeg[i] - curDeg[i];
    if (fabsf(diff) <= step) curDeg[i] = tgtDeg[i];
    else curDeg[i] += (diff > 0 ? step : -step);
    servoWrite(i, curDeg[i]);
  }

  // فعال کردن اپتوها فقط بعد از رسیدن سروو‌ها به home
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
  if (cmd == "all on") { handleOptoCommand(t, n - 1 > 0 ? t : t, 0); }  // جایگزین پایین
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
  if (cmd == "home") {
    goHome();
    Serial.println("Servos -> home");
    return;
  }
  if (t[0] == "speed" && n == 2) {
    float s = t[1].toFloat();
    if (s < 1) s = 1;
    if (s > 200) s = 200;
    speedDps = s;
    Serial.printf("Speed: %.0f deg/s\n", speedDps);
    return;
  }
  if ((t[0] == "s1" || t[0] == "s2") && n == 2) {
    int i = (t[0] == "s1") ? 0 : 1;
    tgtDeg[i] = clampDeg(t[1].toFloat());
    Serial.printf("Servo%d target: %.1f\n", i + 1, tgtDeg[i]);
    return;
  }
  if (t[0] == "s" && n == 3) {
    tgtDeg[0] = clampDeg(t[1].toFloat());
    tgtDeg[1] = clampDeg(t[2].toFloat());
    Serial.printf("Servo targets: %.1f , %.1f\n", tgtDeg[0], tgtDeg[1]);
    return;
  }

  handleOptoCommand(t, n);
}

// ===================== setup / loop =====================
void setup() {
  // اول از همه: اپتوها غیرفعال
  disableAllOptos();

  Serial.begin(115200);
  Serial.setTimeout(30);
  delay(300);

  analogReadResolution(12);
  for (int i = 0; i < 4; i++) analogSetPinAttenuation(ADC_PINS[i], ADC_11db);

  // سروو‌ها (ESP32 Arduino core 3.x)
  for (int i = 0; i < 2; i++) {
    ledcAttach(SERVO_PINS[i], 50, 16);
    curDeg[i] = HOME_DEG[i];
    tgtDeg[i] = HOME_DEG[i];
    servoWrite(i, curDeg[i]);
  }
  delay(1500);          // زمان رسیدن فیزیکی سروو‌ها به home
  homed = true;

  Serial.println("Servos at HOME. Optos OFF.");
  Serial.println("Commands: arm | disarm | home | s1 95 | s2 85 | s 95 85 | speed 30");
  Serial.println("          '1 2 on' | '3 off' | '1 2 2650' | 'all off'");
}

void loop() {
  if (Serial.available()) handleCommand(Serial.readStringUntil('\n'));
  updateServos();

  static uint32_t lastPrint = 0;
  if (millis() - lastPrint >= 1000) {
    lastPrint = millis();
    Serial.printf("S1=%.1f S2=%.1f | ", curDeg[0], curDeg[1]);
    for (int i = 0; i < 4; i++) {
      uint32_t sum = 0;
      for (int k = 0; k < 8; k++) sum += analogReadMilliVolts(ADC_PINS[i]);
      Serial.printf("CH%d %s %4u mV | ", i + 1, enabled[i] ? "ON " : "OFF", sum / 8);
    }
    Serial.println();
  }
}