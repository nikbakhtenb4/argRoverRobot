#include <Arduino.h>

// کانال 0 و 1: نوع 2.6V با DAC داخلی | کانال 2 و 3: نوع 2.8V با Hi-Z / زمین
const uint8_t PINS[4]     = {25, 26, 27, 13};
const uint8_t ADC_PINS[4] = {34, 35, 32, 33};
const bool    IS_DAC[4]   = {true, true, false, false};

const uint32_t VDD_MV = 3300;
const uint32_t MAX_MV = 3000;                      // سقف ایمنی برای DAC

uint32_t targetMv[4] = {2600, 2600, 2600, 2600};   // برای 3 و 4 فقط جهت نمایش
bool     enabled[4]  = {false, false, false, false};

uint8_t mvToDac(uint32_t mv) {
  if (mv > VDD_MV) mv = VDD_MV;
  return (uint8_t)((mv * 255UL + VDD_MV / 2) / VDD_MV);
}

void applyChannel(uint8_t ch) {
  uint8_t pin = PINS[ch];
  if (IS_DAC[ch]) {
    // فعال: ولتاژ هدف | غیرفعال: صفر
    dacWrite(pin, enabled[ch] ? mvToDac(targetMv[ch]) : 0);
  } else {
    if (enabled[ch]) {
      pinMode(pin, INPUT);             // Hi-Z: پول‌آپ برد هوروبرد 2.8V می‌دهد
    } else {
      digitalWrite(pin, LOW);          // ابتدا LOW، بعد خروجی (بدون پرش HIGH)
      pinMode(pin, OUTPUT);            // کشیدن به زمین
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

void handleCommand(String cmd) {
  cmd.trim();
  cmd.toLowerCase();
  if (cmd.length() == 0) return;

  if (cmd == "all off") {
    for (int i = 0; i < 4; i++) { enabled[i] = false; applyChannel(i); }
    Serial.println("ALL OFF");
    return;
  }
  if (cmd == "all on") {
    for (int i = 0; i < 4; i++) { enabled[i] = true; applyChannel(i); }
    Serial.println("ALL ON");
    return;
  }

  int sp = cmd.indexOf(' ');
  if (sp < 0) { Serial.println("Format: <ch 1-4> <on|off|mV>"); return; }

  int ch = cmd.substring(0, sp).toInt();
  String arg = cmd.substring(sp + 1);
  arg.trim();
  if (ch < 1 || ch > 4) { Serial.println("Channel must be 1..4"); return; }
  ch--;

  if (arg == "on") {
    enabled[ch] = true;
  } else if (arg == "off") {
    enabled[ch] = false;
  } else {
    if (!IS_DAC[ch]) { Serial.println("CH3/CH4 have no adjustable voltage (use on/off)"); return; }
    long mv = arg.toInt();
    if (mv < 0) mv = 0;
    if (mv > (long)MAX_MV) mv = MAX_MV;
    targetMv[ch] = mv;
  }
  applyChannel(ch);
  printState(ch);
}

void setup() {
  // اول از همه همه‌ی کانال‌ها غیرفعال شوند
  for (int i = 0; i < 4; i++) { enabled[i] = false; applyChannel(i); }

  Serial.begin(115200);
  Serial.setTimeout(30);
  delay(500);

  analogReadResolution(12);
  for (int i = 0; i < 4; i++) analogSetPinAttenuation(ADC_PINS[i], ADC_11db);

  Serial.println("ESP32 4-channel opto simulator");
  Serial.println("CH1,CH2: 2.6V DAC | CH3,CH4: Hi-Z(active) / LOW(inactive)");
  Serial.println("Commands: '1 on' | '3 off' | '2 2650' | 'all off' | 'all on'");
}

void loop() {
  if (Serial.available()) {
    handleCommand(Serial.readStringUntil('\n'));
  }

  static uint32_t lastPrint = 0;
  if (millis() - lastPrint >= 500) {
    lastPrint = millis();
    for (int i = 0; i < 4; i++) {
      uint32_t sum = 0;
      for (int k = 0; k < 8; k++) sum += analogReadMilliVolts(ADC_PINS[i]);
      Serial.printf("CH%d %s meas=%4u mV | ", i + 1, enabled[i] ? "ON " : "OFF", sum / 8);
    }
    Serial.println();
  }
}