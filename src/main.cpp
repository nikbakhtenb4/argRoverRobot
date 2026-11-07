
#include <Arduino.h>

#define DAC_PIN 25
#define ADC_PIN 34

const uint8_t DAC_VALUE = 167;

bool outputEnabled = false;

void setup() {
    Serial.begin(115200);
    delay(1000);

    analogReadResolution(12);
    analogSetPinAttenuation(ADC_PIN, ADC_11db);

    dacWrite(DAC_PIN, 0);

    Serial.println("ESP32 Voltage Control");
    Serial.println("Send 1 = ON (2.1V)");
    Serial.println("Send 0 = OFF (0V)");
}

void loop() {

    // دریافت فرمان از Serial Monitor
    if (Serial.available()) {
        char command = Serial.read();

        if (command == '1') {
            outputEnabled = true;
            dacWrite(DAC_PIN, DAC_VALUE);
            Serial.println("OUTPUT ON");
        }

        if (command == '0') {
            outputEnabled = false;
            dacWrite(DAC_PIN, 0);
            Serial.println("OUTPUT OFF");
        }
    }

    // اندازه گیری ولتاژ
    uint32_t sum = 0;

    for (int i = 0; i < 32; i++) {
        sum += analogReadMilliVolts(ADC_PIN);
        delay(2);
    }

    uint32_t average = sum / 32;

    Serial.print("State: ");
    Serial.print(outputEnabled ? "ON" : "OFF");

    Serial.print(" | DAC: ");
    Serial.print(outputEnabled ? DAC_VALUE : 0);

    Serial.print(" | Voltage: ");
    Serial.print(average);
    Serial.println(" mV");

    delay(500);
}
