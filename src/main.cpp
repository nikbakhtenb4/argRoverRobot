#include <Arduino.h>
#include <Wire.h>
#include <math.h>

// ======================= اپتوها =======================
// کانال 0 و 1: 2.6V با DAC | کانال 2 و 3: 2.8V با Hi-Z / زمین
const uint8_t PINS[4]     = {25, 26, 27, 13};
const uint8_t ADC_PINS[4] = {34, 35, 32, 33};
const bool    IS_DAC[4]   = {true, true, false, false};
const uint32_t VDD_MV = 3300;
const uint32_t MAX_MV = 3000;
uint32_t targetMv[4] = {2630, 2630, 2800, 2800};
bool     enabled[4]  = {false, false, false, false};

// ======================= رله =======================
const uint8_t RELAY_PIN = 4;
const bool    RELAY_ACTIVE_LOW = true;
bool     relayOn = false;
uint32_t relayChangedAt = 0;
String   relayReason = "boot (default OFF)";

// ======================= سروو (۲ عدد) =======================
const uint8_t  NSERVO = 2;
const uint8_t  SERVO_PINS[2]   = {18, 19};
const uint8_t  SERVO_CH[2]     = {0, 1};
const char*    SERVO_NAME[2]   = {"LEFT-A", "LEFT-B"};
const bool     SERVO_INVERT[2] = {false, false};
const float    RANGE_DEG       = 180.0f;
const uint16_t SERVO_MIN_US    = 500;
const uint16_t SERVO_MAX_US    = 2500;
const float    HOME_DEG[2]     = {100, 105};
const float    LIMIT_MIN       = 60;
const float    LIMIT_MAX       = 140;
const float    DEFAULT_STEP    = 5.0f;   // مقدار پیش‌فرض دستورهای u / d (درجه)

float curDeg[2] = {100, 105};
float velDps[2] = {0, 0};
float tgtDeg[2] = {100, 105};
float smoothTime = 0.5f;
float maxSpeed   = 30.0f;

// ======================= ژیروسکوپ (MPU6050) =======================
const uint8_t SDA_PIN = 21, SCL_PIN = 22;
const uint8_t GYRO_ADDR[2] = {0x68, 0x69};
const char*   GYRO_NAME[2] = {"LEFT", "RIGHT"};
const float   FLAT_TOL_DEG = 2.0f;
const uint32_t FLAT_HOLD_MS = 1000;
const uint32_t FLAT_TIMEOUT_MS = 5000;

struct Gyro {
  bool ok = false;
  bool init = false;
  uint8_t fail = 0;
  float roll = 0, pitch = 0;
  float rawRoll = 0, rawPitch = 0;
  float rollOff = 0, pitchOff = 0;
  float gx = 0, gy = 0, gz = 0;
  float ax = 0, ay = 0, az = 0;
  float tempC = 0;
  float biasX = 0, biasY = 0, biasZ = 0;
};
Gyro gyro[2];

// ======================= حرکت‌ها =======================
// ماسک اپتو (بیت0=CH1 ... بیت3=CH4) و زاویه دو سروو. فعلاً همه روی home.
struct Motion { const char* name; uint8_t optoMask; float servo[2]; };
Motion MOTIONS[4] = {
  {"FORWARD", 0x0F, {100, 105}},
  {"BACK",    0x0F, {100, 105}},
  {"LEFT",    0x0F, {100, 105}},
  {"RIGHT",   0x0F, {100, 105}},
};
enum { M_FWD = 0, M_BACK, M_LEFT, M_RIGHT };
int  curMotion = -1;
const uint32_t STOP_HOME_DELAY_MS = 500;
bool stopPending = false;
uint32_t stopAt = 0;

// ======================= روند بوت =======================
enum BootState { B_HOMING, B_CHECK, B_WAIT_OK, B_DONE };
BootState bootState = B_HOMING;
uint32_t bootT = 0, flatSince = 0;
bool userOk = false;
const uint32_t HOME_SETTLE_MS = 1500;

// ======================= ابزار لاگ =======================
void logTs() {
  unsigned long m = millis();
  Serial.printf("[LOG %lu.%03lu] ", m / 1000, m % 1000);
}

// ======================= رله =======================
void relayInit() {
  digitalWrite(RELAY_PIN, RELAY_ACTIVE_LOW ? HIGH : LOW);
  pinMode(RELAY_PIN, OUTPUT);
  relayOn = false;
}

void relaySet(bool on, const char* reason) {
  if (on == relayOn) {
    logTs(); Serial.printf("RELAY already %s\n", on ? "ON" : "OFF");
    return;
  }
  digitalWrite(RELAY_PIN, (on != RELAY_ACTIVE_LOW) ? HIGH : LOW);
  relayOn = on;
  relayChangedAt = millis();
  relayReason = reason;
  logTs(); Serial.printf("RELAY -> %s (%s)\n", on ? "ON" : "OFF", reason);
}

void printRelay() {
  unsigned long ago = (millis() - relayChangedAt) / 1000;
  Serial.printf("RELAY: %s | since %lu s ago | reason: %s\n",
                relayOn ? "ON" : "OFF", ago, relayReason.c_str());
}

// ======================= اپتو =======================
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
      pinMode(pin, INPUT);        // Hi-Z: پول‌آپ برد هوروبرد
    } else {
      digitalWrite(pin, LOW);
      pinMode(pin, OUTPUT);       // کشیدن به زمین
    }
  }
}

void printState(uint8_t ch) {
  if (IS_DAC[ch])
    Serial.printf("CH%d: %s | %u mV (DAC)\n", ch + 1, enabled[ch] ? "ON" : "OFF", (unsigned)targetMv[ch]);
  else
    Serial.printf("CH%d: %s | %s\n", ch + 1, enabled[ch] ? "ON" : "OFF",
                  enabled[ch] ? "Hi-Z (2.8V from board)" : "pulled LOW");
}

void optoApplyMask(uint8_t mask, const char* why) {
  for (int i = 0; i < 4; i++) {
    enabled[i] = (mask >> i) & 1;
    applyChannel(i);
  }
  logTs();
  Serial.printf("OPTO -> CH1..4 = %d%d%d%d (%s)\n",
                enabled[0], enabled[1], enabled[2], enabled[3], why);
}

void printOptos() {
  Serial.println("--- OPTO ---");
  for (int i = 0; i < 4; i++) {
    uint32_t sum = 0;
    for (int k = 0; k < 8; k++) sum += analogReadMilliVolts(ADC_PINS[i]);
    char mode[32];
    if (IS_DAC[i]) snprintf(mode, sizeof(mode), "DAC %umV", (unsigned)targetMv[i]);
    else snprintf(mode, sizeof(mode), "%s", enabled[i] ? "Hi-Z" : "LOW");
    Serial.printf("CH%d %-3s | %-10s | meas=%4u mV\n", i + 1, enabled[i] ? "ON" : "OFF", mode, (unsigned)(sum / 8));
  }
}

// ======================= سروو =======================
float servoPulseUs(uint8_t i, float deg) {
  float a = SERVO_INVERT[i] ? (RANGE_DEG - deg) : deg;
  return SERVO_MIN_US + (a / RANGE_DEG) * (SERVO_MAX_US - SERVO_MIN_US);
}

void servoWrite(uint8_t i, float deg) {
  float us = servoPulseUs(i, deg);
  uint32_t duty = (uint32_t)(us / 20000.0f * 65535.0f + 0.5f);   // 50Hz, 16 بیت
  ledcWrite(SERVO_CH[i], duty);
}

float clampDeg(float d) {
  if (d < LIMIT_MIN) return LIMIT_MIN;
  if (d > LIMIT_MAX) return LIMIT_MAX;
  return d;
}

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
  for (int i = 0; i < NSERVO; i++)
    if (fabsf(curDeg[i] - HOME_DEG[i]) > 0.1f || fabsf(velDps[i]) > 0.5f) return false;
  return true;
}

void goHome() {
  for (int i = 0; i < NSERVO; i++) tgtDeg[i] = HOME_DEG[i];
}

void updateServos() {
  static uint32_t lastUs = micros();
  uint32_t nowUs = micros();
  float dt = (nowUs - lastUs) / 1e6f;
  if (dt < 0.005f) return;
  lastUs = nowUs;
  if (dt > 0.05f) dt = 0.05f;
  for (int i = 0; i < NSERVO; i++) {
    curDeg[i] = smoothDamp(curDeg[i], tgtDeg[i], velDps[i], smoothTime, maxSpeed, dt);
    servoWrite(i, curDeg[i]);
  }
}

void printServos() {
  Serial.printf("--- SERVO --- smooth=%.2fs maxSpeed=%.0f deg/s limits=%.0f..%.0f\n",
                smoothTime, maxSpeed, LIMIT_MIN, LIMIT_MAX);
  for (int i = 0; i < NSERVO; i++) {
    Serial.printf("S%d %-6s pin=%d last=%.2f tgt=%.2f vel=%+.2f deg/s pulse=%.0f us home=%.0f\n",
                  i + 1, SERVO_NAME[i], SERVO_PINS[i], curDeg[i], tgtDeg[i], velDps[i],
                  servoPulseUs(i, curDeg[i]), HOME_DEG[i]);
  }
}

// ======================= ژیروسکوپ =======================
void i2cRecover() {
  Wire.end();
  pinMode(SCL_PIN, OUTPUT);
  pinMode(SDA_PIN, INPUT_PULLUP);
  for (int i = 0; i < 9; i++) {
    digitalWrite(SCL_PIN, HIGH); delayMicroseconds(5);
    digitalWrite(SCL_PIN, LOW);  delayMicroseconds(5);
  }
  pinMode(SCL_PIN, INPUT_PULLUP);
  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(50000);
  Wire.setTimeOut(50);
}

bool mpuWriteReg(uint8_t addr, uint8_t reg, uint8_t val) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  Wire.write(val);
  return Wire.endTransmission() == 0;
}

bool mpuInit(int i) {
  uint8_t a = GYRO_ADDR[i];
  Wire.beginTransmission(a);
  if (Wire.endTransmission() != 0) { gyro[i].ok = false; return false; }
  mpuWriteReg(a, 0x6B, 0x00);
  mpuWriteReg(a, 0x1A, 0x03);
  mpuWriteReg(a, 0x1B, 0x00);
  mpuWriteReg(a, 0x1C, 0x00);
  gyro[i].ok = true;
  gyro[i].init = false;
  gyro[i].fail = 0;
  return true;
}

bool mpuRead(int i, int16_t *v) {
  uint8_t a = GYRO_ADDR[i];
  Wire.beginTransmission(a);
  Wire.write(0x3B);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom((int)a, 14) != 14) return false;
  for (int k = 0; k < 7; k++) {
    int hi = Wire.read();
    int lo = Wire.read();
    v[k] = (int16_t)((hi << 8) | lo);
  }
  return true;
}

void calibrateGyroBias() {
  for (int i = 0; i < 2; i++) {
    if (!gyro[i].ok) continue;
    float sx = 0, sy = 0, sz = 0;
    int n = 0;
    for (int k = 0; k < 200; k++) {
      int16_t v[7];
      if (mpuRead(i, v)) { sx += v[4] / 131.0f; sy += v[5] / 131.0f; sz += v[6] / 131.0f; n++; }
      delay(5);
    }
    if (n > 0) { gyro[i].biasX = sx / n; gyro[i].biasY = sy / n; gyro[i].biasZ = sz / n; }
  }
}

void updateGyro() {
  static uint32_t lastMs = millis();
  static uint32_t lastRetry = 0;
  uint32_t now = millis();
  if (now - lastMs < 10) return;
  float dt = (now - lastMs) / 1000.0f;
  lastMs = now;
  if (dt > 0.1f) dt = 0.1f;

  if (now - lastRetry > 2000) {
    lastRetry = now;
    for (int i = 0; i < 2; i++) if (!gyro[i].ok) mpuInit(i);
  }

  for (int i = 0; i < 2; i++) {
    Gyro &g = gyro[i];
    if (!g.ok) continue;
    int16_t v[7];
    if (!mpuRead(i, v)) {
      if (++g.fail > 20) {
        g.ok = false;
        g.fail = 0;
        logTs(); Serial.printf("GYRO %s lost, recovering I2C bus\n", GYRO_NAME[i]);
        i2cRecover();
      }
      continue;
    }
    g.fail = 0;
    g.ax = v[0] / 16384.0f; g.ay = v[1] / 16384.0f; g.az = v[2] / 16384.0f;
    g.tempC = v[3] / 340.0f + 36.53f;
    g.gx = v[4] / 131.0f - g.biasX;
    g.gy = v[5] / 131.0f - g.biasY;
    g.gz = v[6] / 131.0f - g.biasZ;

    float accRoll  = atan2f(g.ay, g.az) * 57.29578f;
    float accPitch = atan2f(-g.ax, sqrtf(g.ay * g.ay + g.az * g.az)) * 57.29578f;
    if (!g.init) {
      g.rawRoll = accRoll; g.rawPitch = accPitch; g.init = true;
    } else {
      g.rawRoll  = 0.98f * (g.rawRoll  + g.gx * dt) + 0.02f * accRoll;
      g.rawPitch = 0.98f * (g.rawPitch + g.gy * dt) + 0.02f * accPitch;
    }
    g.roll  = g.rawRoll  - g.rollOff;
    g.pitch = g.rawPitch - g.pitchOff;
  }
}

bool gyroFlat(int i) {
  return gyro[i].ok && fabsf(gyro[i].roll) <= FLAT_TOL_DEG && fabsf(gyro[i].pitch) <= FLAT_TOL_DEG;
}
bool allFlat() { return gyroFlat(0) && gyroFlat(1); }

void printGyro() {
  Serial.println("--- GYRO ---");
  for (int i = 0; i < 2; i++) {
    Gyro &g = gyro[i];
    if (!g.ok) {
      Serial.printf("%-5s (0x%02X): NOT FOUND\n", GYRO_NAME[i], GYRO_ADDR[i]);
      continue;
    }
    Serial.printf("%-5s (0x%02X): roll=%+6.2f pitch=%+6.2f | rate x=%+.1f y=%+.1f z=%+.1f deg/s | T=%.1fC | %s\n",
                  GYRO_NAME[i], GYRO_ADDR[i], g.roll, g.pitch, g.gx, g.gy, g.gz, g.tempC,
                  gyroFlat(i) ? "FLAT" : "NOT FLAT");
  }
}

// ======================= حرکت و توقف =======================
void doMotion(int m) {
  if (bootState != B_DONE) { Serial.println("REFUSED: boot sequence not finished"); return; }
  if (!relayOn) Serial.println("WARNING: relay is OFF (hoverboard unpowered)");
  stopPending = false;
  const Motion &mo = MOTIONS[m];
  optoApplyMask(mo.optoMask, mo.name);
  for (int i = 0; i < NSERVO; i++) tgtDeg[i] = clampDeg(mo.servo[i]);
  curMotion = m;
  logTs(); Serial.printf("MOTION: %s | servo targets %.1f %.1f\n",
                         mo.name, tgtDeg[0], tgtDeg[1]);
}

void doStop() {
  optoApplyMask(0, "STOP");
  curMotion = -1;
  stopPending = true;
  stopAt = millis() + STOP_HOME_DELAY_MS;
  logTs(); Serial.println("STOP: optos OFF now, servos -> HOME in 0.5 s");
}

void stopStep() {
  if (stopPending && (int32_t)(millis() - stopAt) >= 0) {
    stopPending = false;
    goHome();
    logTs(); Serial.println("STOP: servos -> HOME");
  }
}

// ======================= روند بوت =======================
const char* bootName() {
  switch (bootState) {
    case B_HOMING:  return "HOMING";
    case B_CHECK:   return "CHECKING GYROS";
    case B_WAIT_OK: return "WAITING FOR 'ok'";
    default:        return "DONE";
  }
}

void bootStep() {
  uint32_t now = millis();
  switch (bootState) {
    case B_HOMING:
      if (now - bootT >= HOME_SETTLE_MS && atHome()) {
        Serial.println("[BOOT] servos at HOME. calibrating gyros (keep still)...");
        calibrateGyroBias();
        bootState = B_CHECK;
        bootT = millis();
        flatSince = 0;
      }
      break;

    case B_CHECK: {
      bool flat = allFlat();
      if (flat) { if (flatSince == 0) flatSince = now; }
      else flatSince = 0;

      if (flat && now - flatSince >= FLAT_HOLD_MS) {
        Serial.println("[BOOT] gyros FLAT -> starting automatically");
        printGyro();
        relaySet(true, "boot: gyros flat");
        bootState = B_DONE;
      } else if (now - bootT >= FLAT_TIMEOUT_MS) {
        Serial.println("[BOOT] NOT FLAT (or gyro missing):");
        printGyro();
        Serial.println("[BOOT] send 'ok' to turn the relay ON anyway, or 'gzero' if the platform is level.");
        bootState = B_WAIT_OK;
        bootT = now;
      }
      break;
    }

    case B_WAIT_OK:
      if (userOk) {
        relaySet(true, "boot: user confirmed");
        bootState = B_DONE;
      } else if (now - bootT >= 5000) {
        bootT = now;
        Serial.println("[BOOT] still waiting for 'ok' (gyros not flat). 'g' shows angles.");
      }
      break;

    default: break;
  }
}

// ======================= دستورها =======================
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

bool isNumberStr(const String &s) {
  if (s.length() == 0) return false;
  bool digit = false;
  for (unsigned i = 0; i < s.length(); i++) {
    char ch = s[i];
    if (isDigit(ch)) digit = true;
    else if (ch == '.' || ((ch == '-' || ch == '+') && i == 0)) continue;
    else return false;
  }
  return digit;
}

bool isDirStr(const String &s) {
  if (s.length() == 0) return false;
  for (unsigned i = 0; i < s.length(); i++)
    if (s[i] != 'u' && s[i] != 'd') return false;
  return true;
}

bool isServoTok(const String &s) { return s == "s1" || s == "s2"; }

void printStatus() {
  Serial.println("========== DEVICE STATUS ==========");
  Serial.printf("Boot: %s | Motion: %s%s\n", bootName(),
                curMotion >= 0 ? MOTIONS[curMotion].name : "none",
                stopPending ? " (stop pending)" : "");
  printRelay();
  printOptos();
  printServos();
  printGyro();
  Serial.println("===================================");
}

void printHelp() {
  Serial.println("--- COMMANDS ---");
  Serial.println("Status : s (all) | o (optos) | r (relay) | g (gyro) | v (servos) | help or ?");
  Serial.println("Motion : fwd | back | left | right | stop (x) | kill (stop + relay off)");
  Serial.println("Relay  : relay on | relay off | r on | r off");
  Serial.println("Home   : h (both) | h s1 | h s2");
  Serial.println("Servo  : s1 95 | s2 85 | s 95 85 | j1 0.2 | j2 -0.2 | smooth 0.8 | speed 20");
  Serial.println("Step   : s1 s2 u [n] | s1 s2 ud [n] | s1 s2 du [n] | s1 d 3   (u=up(+), d=down(-), default 5)");
  Serial.println("Opto   : 1 2 on | 3 4 off | 1 2 2650 | all on | all off | disarm");
  Serial.println("Gyro   : gzero (set current as level) | gcal (recalibrate bias)");
  Serial.println("Boot   : ok (confirm start when gyros not flat)");
}

// "h" یا "h s1" یا "h s1 s2"
bool handleHome(String *t, int n) {
  if (n == 1) { goHome(); logTs(); Serial.println("Servos -> HOME (both)"); return true; }
  bool sel[2] = {false, false};
  for (int i = 1; i < n; i++) {
    if (!isServoTok(t[i])) { Serial.printf("Bad servo '%s' (use s1 / s2)\n", t[i].c_str()); return true; }
    sel[t[i][1] - '1'] = true;
  }
  for (int i = 0; i < NSERVO; i++) if (sel[i]) tgtDeg[i] = HOME_DEG[i];
  logTs(); Serial.printf("Servos -> HOME (%s%s)\n", sel[0] ? "S1 " : "", sel[1] ? "S2" : "");
  return true;
}

// "s1 95" ، "s1 s2 u" ، "s1 s2 ud 10" ، ...
// خروجی true یعنی دستور از نوع سروو بود و پردازش شد
bool handleServoCommand(String *t, int n) {
  int ord[2], cnt = 0;
  bool seen[2] = {false, false};
  int idx = 0;
  while (idx < n && isServoTok(t[idx])) {
    int s = t[idx][1] - '1';
    if (!seen[s]) { seen[s] = true; ord[cnt++] = s; }
    idx++;
  }
  if (cnt == 0) return false;
  if (idx >= n) { Serial.println("Servo command needs: <angle> or <u/d> [n]"); return true; }

  // حالت زاویه مطلق: "s1 95"
  if (isNumberStr(t[idx])) {
    if (cnt != 1 || idx != n - 1) { Serial.println("Absolute angle works with one servo: s1 95  (or: s 95 85)"); return true; }
    tgtDeg[ord[0]] = clampDeg(t[idx].toFloat());
    Serial.printf("Servo%d target: %.2f\n", ord[0] + 1, tgtDeg[ord[0]]);
    return true;
  }

  // حالت جهتی: u / d / ud / du ...
  String dir = t[idx];
  if (!isDirStr(dir)) { Serial.printf("Unknown servo action '%s'\n", dir.c_str()); return true; }
  if (dir.length() != 1 && (int)dir.length() != cnt) {
    Serial.printf("Direction letters (%d) must be 1 or equal to servo count (%d)\n", (int)dir.length(), cnt);
    return true;
  }
  idx++;
  float step = DEFAULT_STEP;
  if (idx < n) {
    if (!isNumberStr(t[idx])) { Serial.printf("Bad amount '%s'\n", t[idx].c_str()); return true; }
    step = fabsf(t[idx].toFloat());
    idx++;
  }
  if (idx < n) { Serial.println("Too many words in servo command"); return true; }

  for (int k = 0; k < cnt; k++) {
    char d = (dir.length() == 1) ? dir[0] : dir[k];
    int s = ord[k];
    float delta = (d == 'u') ? step : -step;
    tgtDeg[s] = clampDeg(tgtDeg[s] + delta);
    Serial.printf("Servo%d %s %.1f -> target %.2f\n", s + 1, d == 'u' ? "UP" : "DOWN", step, tgtDeg[s]);
  }
  return true;
}

void handleOptoCommand(String *tokens, int n) {
  if (n < 2 || !isDigit(tokens[0][0])) {
    Serial.println("Unknown command. Send 'help' for the list.");
    return;
  }
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

  if (isOn && bootState != B_DONE) { Serial.println("REFUSED: boot sequence not finished"); return; }

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
  const String &c = t[0];

  // ---- home ----
  if (c == "h" || c == "home") { handleHome(t, n); return; }

  // ---- سروو: s1 / s2 ----
  if (isServoTok(c)) { handleServoCommand(t, n); return; }

  if (n == 1) {
    // ---- وضعیت‌ها ----
    if (c == "s" || c == "status") { printStatus(); return; }
    if (c == "o" || c == "opto")  { printOptos(); return; }
    if (c == "r" || c == "relay") { printRelay(); return; }
    if (c == "g" || c == "gyro")  { printGyro(); return; }
    if (c == "v" || c == "servo") { printServos(); return; }
    if (c == "help" || c == "?")  { printHelp(); return; }

    // ---- حرکت ----
    if (c == "fwd" || c == "forward" || c == "straight") { doMotion(M_FWD); return; }
    if (c == "back")  { doMotion(M_BACK); return; }
    if (c == "left")  { doMotion(M_LEFT); return; }
    if (c == "right") { doMotion(M_RIGHT); return; }
    if (c == "stop" || c == "x") { doStop(); return; }
    if (c == "kill") { doStop(); relaySet(false, "KILL command"); return; }

    // ---- متفرقه ----
    if (c == "disarm") { optoApplyMask(0, "disarm"); return; }
    if (c == "ok") {
      if (bootState == B_WAIT_OK) { userOk = true; Serial.println("OK received"); }
      else Serial.println("Nothing to confirm right now");
      return;
    }
    if (c == "gzero") {
      for (int i = 0; i < 2; i++) {
        if (!gyro[i].ok) continue;
        gyro[i].rollOff = gyro[i].rawRoll;
        gyro[i].pitchOff = gyro[i].rawPitch;
      }
      Serial.println("Gyro level reference set (platform must be level now)");
      printGyro();
      return;
    }
    if (c == "gcal") {
      Serial.println("Calibrating gyro bias (keep still)...");
      calibrateGyroBias();
      Serial.println("Done");
      return;
    }
  }

  if (n == 2) {
    if (c == "relay" || c == "r") {
      if (t[1] == "on")  { relaySet(true, "manual command");  return; }
      if (t[1] == "off") { relaySet(false, "manual command"); return; }
    }
    if (c == "all" && (t[1] == "on" || t[1] == "off")) {
      if (t[1] == "on") {
        if (bootState != B_DONE) { Serial.println("REFUSED: boot sequence not finished"); return; }
        optoApplyMask(0x0F, "all on");
      } else {
        optoApplyMask(0, "all off");
      }
      return;
    }
    if (c == "smooth") {
      float s = t[1].toFloat();
      if (s < 0.05f) s = 0.05f;
      if (s > 5.0f)  s = 5.0f;
      smoothTime = s;
      Serial.printf("Smooth time: %.2f s\n", smoothTime);
      return;
    }
    if (c == "speed") {
      float s = t[1].toFloat();
      if (s < 1) s = 1;
      if (s > 200) s = 200;
      maxSpeed = s;
      Serial.printf("Max speed: %.0f deg/s\n", maxSpeed);
      return;
    }
    if (c == "j1" || c == "j2") {
      int i = c[1] - '1';
      tgtDeg[i] = clampDeg(tgtDeg[i] + t[1].toFloat());
      Serial.printf("Servo%d target: %.2f\n", i + 1, tgtDeg[i]);
      return;
    }
  }

  // "s 95 85" : هر دو سروو با زاویه مطلق
  if (n == 3 && c == "s") {
    for (int i = 0; i < NSERVO; i++) tgtDeg[i] = clampDeg(t[i + 1].toFloat());
    Serial.printf("Servo targets: %.2f , %.2f\n", tgtDeg[0], tgtDeg[1]);
    return;
  }

  handleOptoCommand(t, n);
}

// ======================= setup / loop =======================
void setup() {
  // مرحله ۱: رله خاموش و همه اپتوها غیرفعال (قبل از هر کار دیگر)
  relayInit();
  for (int i = 0; i < 4; i++) { enabled[i] = false; applyChannel(i); }

  Serial.begin(115200);
  Serial.setTimeout(30);
  delay(300);
  Serial.println("\n[BOOT] relay OFF, optos OFF");

  analogReadResolution(12);
  for (int i = 0; i < 4; i++) analogSetPinAttenuation(ADC_PINS[i], ADC_11db);

  // مرحله ۲: سروو‌ها به home (ESP32 Arduino core 2.x)
  for (int i = 0; i < NSERVO; i++) {
    ledcSetup(SERVO_CH[i], 50, 16);
    ledcAttachPin(SERVO_PINS[i], SERVO_CH[i]);
    curDeg[i] = HOME_DEG[i];
    tgtDeg[i] = HOME_DEG[i];
    servoWrite(i, curDeg[i]);
  }
  Serial.println("[BOOT] servos -> HOME");

  // ژیروسکوپ‌ها
  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(50000);
  Wire.setTimeOut(50);
  for (int i = 0; i < 2; i++) {
    bool ok = mpuInit(i);
    Serial.printf("[BOOT] gyro %s (0x%02X): %s\n", GYRO_NAME[i], GYRO_ADDR[i], ok ? "found" : "NOT FOUND");
  }

  bootState = B_HOMING;
  bootT = millis();
  Serial.println("Send 'help' for the command list.");
}

void loop() {
  if (Serial.available()) handleCommand(Serial.readStringUntil('\n'));
  updateServos();
  updateGyro();
  bootStep();
  stopStep();
}