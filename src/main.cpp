
#define OPTO_PIN 27

volatile uint32_t pulseCount = 0;

void IRAM_ATTR onPulse() {
  pulseCount++;
}

void setup() {
  Serial.begin(115200);

  pinMode(OPTO_PIN, INPUT);

  attachInterrupt(
    digitalPinToInterrupt(OPTO_PIN),
    onPulse,
    RISING
  );

  Serial.println("ST150 Opto Counter Reader");
  Serial.println("Waiting for pulses...");
}

void loop() {
  static uint32_t lastTime = 0;

  if (millis() - lastTime >= 1000) {
    lastTime += 1000;

    noInterrupts();
    uint32_t count = pulseCount;
    pulseCount = 0;
    interrupts();

    Serial.print("Pulses per second: ");
    Serial.println(count);
  }
}
