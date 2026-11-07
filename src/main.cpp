
#include <Arduino.h>

#define OPTO_ANALOG_PIN 34

void setup() {
    Serial.begin(115200);
    delay(1000);

    analogReadResolution(12);
    analogSetPinAttenuation(OPTO_ANALOG_PIN, ADC_11db);

    Serial.println();
    Serial.println("================================");
    Serial.println(" ESP32 - ST150 Analog Reader");
    Serial.println(" Board: ESP32-WROOM-32");
    Serial.println(" ADC Pin: GPIO34");
    Serial.println("================================");
}

void loop() {
    int raw = analogRead(OPTO_ANALOG_PIN);
    uint32_t voltage = analogReadMilliVolts(OPTO_ANALOG_PIN);

    Serial.print("ADC Raw: ");
    Serial.print(raw);

    Serial.print(" | Voltage: ");
    Serial.print(voltage);
    Serial.println(" mV");

    delay(10);
}
