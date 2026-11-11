#pragma once
#include "esp_http_server.h"

void ota_boot_watchdog_start(void);
void ota_boot_confirm_ready(void);
void ota_register(httpd_handle_t server, const char *device, const char *boot);
