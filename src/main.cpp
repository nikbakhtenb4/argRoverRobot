
#include <Arduino.h>

#define DAC_PIN 25
#define ADC_PIN 34

// Initial DAC value for approximately 2.1V
uint8_t dacValue = 167;

void setup() {
    Serial.begin(115200);
    delay(1000);

    analogReadResolution(12);
    analogSetPinAttenuation(ADC_PIN, ADC_11db);

    dacWrite(DAC_PIN, dacValue);

    Serial.println("ESP32 Constant Voltage Test");
    Serial.println("Target: 2.1V");
    Serial.println("DAC: GPIO25");
    Serial.println("ADC: GPIO34");
}

void loop() {
    uint32_t sum = 0;

    for (int i = 0; i < 32; i++) {
        sum += analogReadMilliVolts(ADC_PIN);
        delay(2);
    }

    uint32_t average = sum / 32;

    Serial.print("DAC Value: ");
    Serial.print(dacValue);

    Serial.print(" | Average Voltage: ");
    Serial.print(average);

    Serial.println(" mV");

    delay(500);
}
