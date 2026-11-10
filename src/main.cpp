
#include <Arduino.h>
#include <Wire.h>

// ESP32 I2C pins
#define SDA_PIN 21
#define SCL_PIN 22

// MPU6050 address
#define MPU_ADDR 0x69    //3V3 (آدرس ۰x69)    =AD0

// Registers
#define REG_WHO_AM_I  0x75
#define REG_PWR_MGMT  0x6B
#define REG_ACCEL_CFG 0x1C
#define REG_GYRO_CFG  0x1B
#define REG_DATA      0x3B

// Calibration
const int CAL_SAMPLES = 500;

float gyroOffsetX = 0;
float gyroOffsetY = 0;
float gyroOffsetZ = 0;

uint32_t lastPrint = 0;

int16_t axRaw, ayRaw, azRaw;
int16_t gxRaw, gyRaw, gzRaw;
int16_t tempRaw;

float ax, ay, az;
float gx, gy, gz;
float temperature;

// ---------------- I2C ----------------

bool writeRegister(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

bool readRegisters(uint8_t reg, uint8_t count, uint8_t *data) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);

  if (Wire.endTransmission(false) != 0)
    return false;

  uint8_t received = Wire.requestFrom(
    (uint8_t)MPU_ADDR,
    count,
    (uint8_t)true
  );

  if (received != count)
    return false;

  for (int i = 0; i < count; i++)
    data[i] = Wire.read();

  return true;
}

uint8_t readRegister(uint8_t reg) {
  uint8_t value = 0;

  if (!readRegisters(reg, 1, &value))
    return 0xFF;

  return value;
}

// ---------------- Sensor ----------------

bool readSensor() {
  uint8_t data[14];

  if (!readRegisters(REG_DATA, 14, data))
    return false;

  axRaw = (int16_t)((data[0] << 8) | data[1]);
  ayRaw = (int16_t)((data[2] << 8) | data[3]);
  azRaw = (int16_t)((data[4] << 8) | data[5]);

  tempRaw = (int16_t)((data[6] << 8) | data[7]);

  gxRaw = (int16_t)((data[8] << 8) | data[9]);
  gyRaw = (int16_t)((data[10] << 8) | data[11]);
  gzRaw = (int16_t)((data[12] << 8) | data[13]);

  // Default ranges:
  // Accelerometer: +/- 2g
  // Gyroscope: +/- 250 degrees/second

  ax = axRaw / 16384.0f;
  ay = ayRaw / 16384.0f;
  az = azRaw / 16384.0f;

  gx = gxRaw / 131.0f - gyroOffsetX;
  gy = gyRaw / 131.0f - gyroOffsetY;
  gz = gzRaw / 131.0f - gyroOffsetZ;

  temperature = tempRaw / 340.0f + 36.53f;

  return true;
}

// ---------------- Calibration ----------------

void calibrateGyro() {
  Serial.println();
  Serial.println("================================");
  Serial.println("GYROSCOPE CALIBRATION");
  Serial.println("Keep sensor COMPLETELY STILL!");
  Serial.println("================================");

  double sumX = 0;
  double sumY = 0;
  double sumZ = 0;

  int validSamples = 0;

  for (int i = 0; i < CAL_SAMPLES; i++) {
    if (readSensor()) {
      sumX += gxRaw / 131.0;
      sumY += gyRaw / 131.0;
      sumZ += gzRaw / 131.0;
      validSamples++;
    }

    delay(5);
  }

  if (validSamples == 0) {
    Serial.println("Calibration FAILED!");
    return;
  }

  gyroOffsetX = sumX / validSamples;
  gyroOffsetY = sumY / validSamples;
  gyroOffsetZ = sumZ / validSamples;

  Serial.println("Calibration completed!");

  Serial.printf("Offset X: %.3f deg/s\n", gyroOffsetX);
  Serial.printf("Offset Y: %.3f deg/s\n", gyroOffsetY);
  Serial.printf("Offset Z: %.3f deg/s\n", gyroOffsetZ);
}

// ---------------- Setup ----------------

void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println("\nMPU6050 ESP32 HEALTH TEST");

  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(100000);

  // Check sensor identity
  uint8_t id = readRegister(REG_WHO_AM_I);

  Serial.printf("WHO_AM_I = 0x%02X\n", id);

  if (id != 0x68) {
    Serial.println("ERROR: MPU6050 NOT DETECTED!");
    Serial.println("Check SDA, SCL, VCC and GND.");

    while (true) {
      delay(1000);
    }
  }

  Serial.println("MPU6050 DETECTED!");

  // Wake up sensor
  writeRegister(REG_PWR_MGMT, 0x00);

  // Accelerometer +/- 2g
  writeRegister(REG_ACCEL_CFG, 0x00);

  // Gyroscope +/- 250 deg/s
  writeRegister(REG_GYRO_CFG, 0x00);

  delay(200);

  // Check communication
  if (!readSensor()) {
    Serial.println("ERROR: Cannot read sensor data!");
    while (true) delay(1000);
  }

  calibrateGyro();

  Serial.println();
  Serial.println("TEST STARTED");
  Serial.println("Keep still, then rotate sensor on each axis.");
  Serial.println("-------------------------------------------");
}

// ---------------- Loop ----------------

void loop() {
  if (!readSensor()) {
    Serial.println("I2C READ ERROR!");
    delay(500);
    return;
  }

  if (millis() - lastPrint >= 200) {
    lastPrint = millis();

    float accelMagnitude = sqrt(
      ax * ax + ay * ay + az * az
    );

    Serial.printf(
      "ACC[g] X:%7.3f Y:%7.3f Z:%7.3f | "
      "GYRO[dps] X:%8.2f Y:%8.2f Z:%8.2f | "
      "TEMP:%5.1f C | ACC_MAG:%5.3f\n",
      ax, ay, az,
      gx, gy, gz,
      temperature,
      accelMagnitude
    );
  }
}
