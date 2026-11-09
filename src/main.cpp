#include <Arduino.h>
#define OPTO_PIN 34

volatile uint32_t pulseCount = 0;
bool state = false;
const uint32_t HIGH_TH = 1800;  // mV بالاتر از این: HIGH
const uint32_t LOW_TH  = 900;   // mV پایین‌تر از این: LOW

void setup() {
    Serial.begin(115200);
    analogReadResolution(12);
    analogSetPinAttenuation(OPTO_PIN, ADC_11db);
    pinMode(OPTO_PIN, INPUT);
}

void loop() {
    uint32_t mv = analogReadMilliVolts(OPTO_PIN);

    if (!state && mv > HIGH_TH) {
        state = true;
        pulseCount++;
        Serial.printf("Pulse %lu | %lu mV\n", pulseCount, mv);
    } else if (state && mv < LOW_TH) {
        state = false;
    }
    delay(1);
}