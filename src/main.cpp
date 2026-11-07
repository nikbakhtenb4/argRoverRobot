#include <Arduino.h>

#define DAC_PIN 25
#define ADC_PIN 34

const uint32_t VDD_MV = 3300;   // DAC بین 0 تا حدود 3.3V
uint32_t targetMv = 2600;        // ولتاژ تست اولیه: 0.5V
bool outputEnabled = false;

uint8_t mvToDac(uint32_t mv) {
  if (mv > VDD_MV) mv = VDD_MV;
  return (uint8_t)((mv * 255UL + VDD_MV / 2) / VDD_MV);
}

void applyOutput() {
  dacWrite(DAC_PIN, outputEnabled ? mvToDac(targetMv) : 0);
}

void handleCommand(String cmd) {
  cmd.trim();
  if (cmd.length() == 0) return;

  if (cmd == "1") {
    outputEnabled = true;
    applyOutput();
    Serial.printf("OUTPUT ON  (%u mV, DAC=%u)\n", targetMv, mvToDac(targetMv));
  } else if (cmd == "0") {
    outputEnabled = false;
    applyOutput();
    Serial.println("OUTPUT OFF");
  } else if (cmd[0] == 'v' || cmd[0] == 'V') {
    long mv = cmd.substring(1).toInt();
    if (mv < 0) mv = 0;
    if (mv > 3000) mv = 3000;      // سقف ایمنی
    targetMv = mv;
    applyOutput();
    Serial.printf("Target set: %u mV (DAC=%u)\n", targetMv, mvToDac(targetMv));
  }
}

void setup() {
  Serial.begin(115200);
  Serial.setTimeout(30);
  delay(500);

  analogReadResolution(12);
  analogSetPinAttenuation(ADC_PIN, ADC_11db);

  dacWrite(DAC_PIN, 0);

  Serial.println("ESP32 opto simulator");
  Serial.println("1 = ON | 0 = OFF | v<mV> = set voltage (e.g. v500)");
}

void loop() {
  if (Serial.available()) {
    handleCommand(Serial.readStringUntil('\n'));
  }

  static uint32_t lastPrint = 0;
  if (millis() - lastPrint >= 500) {
    lastPrint = millis();

    uint32_t sum = 0;
    for (int i = 0; i < 16; i++) sum += analogReadMilliVolts(ADC_PIN);
    uint32_t avg = sum / 16;

    Serial.printf("State: %s | Target: %u mV | DAC: %u | Measured: %u mV\n",
                  outputEnabled ? "ON" : "OFF", targetMv,
                  outputEnabled ? mvToDac(targetMv) : 0, avg);
  }
}