// OTA shares the paired mTLS listener. No unauthenticated update service exists.
#include "ota_update.h"
#include "hardware_runtime.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include "esp_app_desc.h"
#include "esp_app_format.h"
#include "esp_ota_ops.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "mbedtls/sha256.h"
#include "cJSON.h"
#include "sdkconfig.h"

#ifndef CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE
#error "OTA requires bootloader rollback support"
#endif

#define OTA_CHUNK 4096
#define OTA_TIMEOUT_US (120LL * 1000000)
#define IMAGE_PREFIX (sizeof(esp_image_header_t) + sizeof(esp_image_segment_header_t) + sizeof(esp_app_desc_t))
static const char *device_id, *boot_id;
static bool ready, reboot_pending;
static esp_timer_handle_t boot_timer, reboot_timer;

static void restart(void *unused) { (void)unused; esp_restart(); }

void ota_boot_watchdog_start(void) {
    esp_ota_img_states_t state;
    if (esp_ota_get_state_partition(esp_ota_get_running_partition(), &state) == ESP_OK &&
        state == ESP_OTA_IMG_PENDING_VERIFY) {
        const esp_timer_create_args_t args = {.callback=restart, .name="ota-verify"};
        ESP_ERROR_CHECK(esp_timer_create(&args, &boot_timer));
        // A new image stuck waiting for Wi-Fi/time must not wait forever.
        ESP_ERROR_CHECK(esp_timer_start_once(boot_timer, 180LL * 1000000));
        printf("OTA_PENDING_VERIFY %s\n", esp_ota_get_running_partition()->label);
        fflush(stdout);
    }
}

void ota_boot_confirm_ready(void) {
    if (boot_timer) {
        ESP_ERROR_CHECK(esp_ota_mark_app_valid_cancel_rollback());
        ESP_ERROR_CHECK(esp_timer_stop(boot_timer));
        ESP_ERROR_CHECK(esp_timer_delete(boot_timer)); boot_timer = NULL;
    }
    ready = true;
    printf("OTA_READY %s\n", esp_app_get_description()->version);
}

static esp_err_t json_reply(httpd_req_t *req, const char *status, const char *body) {
    httpd_resp_set_status(req, status);
    httpd_resp_set_type(req, "application/json");
    httpd_resp_set_hdr(req, "Cache-Control", "no-store");
    httpd_resp_set_hdr(req, "Connection", "close");
    return httpd_resp_sendstr(req, body);
}

static esp_err_t reject(httpd_req_t *req, const char *status, const char *error) {
    char response[96]; snprintf(response, sizeof(response), "{\"error\":\"%s\"}", error);
    json_reply(req, status, response);
    // Discard any unread body with the connection, never parse it as another request.
    return ESP_FAIL;
}

static esp_err_t status(httpd_req_t *req) {
    const esp_partition_t *running = esp_ota_get_running_partition();
    const esp_partition_t *next = esp_ota_get_next_update_partition(NULL);
    const esp_partition_t *boot = esp_ota_get_boot_partition();
    esp_ota_img_states_t state = ESP_OTA_IMG_UNDEFINED;
    esp_ota_get_state_partition(running, &state);
    esp_ota_img_states_t next_state = ESP_OTA_IMG_UNDEFINED;
    if (next) esp_ota_get_state_partition(next, &next_state);
    const esp_app_desc_t *app = esp_app_get_description();
    // esp_app_get_elf_sha256() may return only CONFIG_APP_RETRIEVE_LEN_ELF_SHA
    // characters. OTA verification requires all 256 bits, not the panic-log prefix.
    char elf[65]; static const char digits[] = "0123456789abcdef";
    for (size_t i=0; i<32; ++i) {
        elf[2*i]=digits[app->app_elf_sha256[i]>>4]; elf[2*i+1]=digits[app->app_elf_sha256[i]&15];
    }
    elf[64]=0;
    cJSON *o = cJSON_CreateObject();
    if (!o) return reject(req,"503 Service Unavailable","memory");
    cJSON_AddNumberToObject(o,"protocol",1);
    cJSON_AddStringToObject(o,"device",device_id);
    cJSON_AddStringToObject(o,"boot",boot_id);
    cJSON_AddStringToObject(o,"version",app->version);
    cJSON_AddStringToObject(o,"project",app->project_name);
    cJSON_AddStringToObject(o,"elf_sha256",elf);
    cJSON_AddStringToObject(o,"running",running->label);
    cJSON_AddStringToObject(o,"boot_partition",boot ? boot->label : "");
    cJSON_AddStringToObject(o,"next",next ? next->label : "");
    cJSON_AddNumberToObject(o,"max_bytes",next ? next->size : 0);
    cJSON_AddNumberToObject(o,"image_state",state);
    cJSON_AddNumberToObject(o,"next_image_state",next_state);
    cJSON_AddBoolToObject(o,"ready",ready && !reboot_pending);
    cJSON_AddBoolToObject(o,"rollback_enabled",true);
    cJSON_AddNumberToObject(o,"free_heap",esp_get_free_heap_size());
    cJSON_AddNumberToObject(o,"minimum_free_heap",esp_get_minimum_free_heap_size());
    cJSON_AddStringToObject(o,"phase",reboot_pending ? "reboot_pending" : ready ? "idle" : "boot_verifying");
    char *encoded = cJSON_PrintUnformatted(o); cJSON_Delete(o);
    if (!encoded) return reject(req,"503 Service Unavailable","memory");
    esp_err_t rc = json_reply(req,"200 OK",encoded); free(encoded); return rc;
}

static int unhex(char c) {
    if (c >= '0' && c <= '9') return c-'0';
    if (c >= 'a' && c <= 'f') return c-'a'+10;
    if (c >= 'A' && c <= 'F') return c-'A'+10;
    return -1;
}
static bool header(httpd_req_t *req, const char *key, char *out, size_t size) {
    size_t n = httpd_req_get_hdr_value_len(req,key);
    return n > 0 && n < size && httpd_req_get_hdr_value_str(req,key,out,size) == ESP_OK;
}
static bool receive(httpd_req_t *req, uint8_t *buf, size_t size, int64_t deadline) {
    size_t received = 0;
    while (received < size && esp_timer_get_time() < deadline) {
        int n = httpd_req_recv(req,(char *)buf+received,size-received);
        if (n <= 0) return false; // Includes timeout: bounded, no retry loop.
        received += (size_t)n;
    }
    return received == size && esp_timer_get_time() < deadline;
}

static esp_err_t upload(httpd_req_t *req) {
    if (!ready || reboot_pending) return reject(req,"409 Conflict","not_ready");
    char boot[33], hex[65], type[40]; uint8_t expected[32];
    if (!header(req,"X-Rastag-Boot",boot,sizeof(boot)) || strcmp(boot,boot_id))
        return reject(req,"409 Conflict","stale_boot");
    if (!header(req,"Content-Type",type,sizeof(type)) || strcmp(type,"application/octet-stream") ||
        httpd_req_get_hdr_value_len(req,"Transfer-Encoding") != 0 ||
        !header(req,"X-Firmware-SHA256",hex,sizeof(hex)) || strlen(hex) != 64)
        return reject(req,"400 Bad Request","invalid_headers");
    for (size_t i = 0; i < 32; ++i) {
        int a = unhex(hex[2*i]), b = unhex(hex[2*i+1]);
        if (a < 0 || b < 0) return reject(req,"400 Bad Request","invalid_sha256");
        expected[i] = (uint8_t)(a*16+b);
    }
    const esp_partition_t *target = esp_ota_get_next_update_partition(NULL);
    if (!target || target == esp_ota_get_running_partition() || req->content_len < IMAGE_PREFIX || req->content_len > target->size)
        return reject(req,"413 Content Too Large","image_size");

    uint8_t prefix[IMAGE_PREFIX];
    int64_t deadline = esp_timer_get_time() + OTA_TIMEOUT_US;
    if (!receive(req,prefix,sizeof(prefix),deadline)) return reject(req,"408 Request Timeout","incomplete_header");
    esp_image_header_t image; esp_app_desc_t app;
    memcpy(&image,prefix,sizeof(image));
    memcpy(&app,prefix+sizeof(image)+sizeof(esp_image_segment_header_t),sizeof(app));
    if (image.magic != ESP_IMAGE_HEADER_MAGIC || image.chip_id != ESP_CHIP_ID_ESP32 ||
        app.magic_word != ESP_APP_DESC_MAGIC_WORD ||
        memcmp(app.project_name,esp_app_get_description()->project_name,sizeof(app.project_name)) ||
        !memchr(app.version,0,sizeof(app.version)))
        return reject(req,"400 Bad Request","wrong_image");

    uint8_t *chunk = malloc(OTA_CHUNK);
    if (!chunk) return reject(req,"503 Service Unavailable","memory");
    if (!hardware_maintenance_begin()) { free(chunk); return reject(req,"409 Conflict","motion_or_maintenance_active"); }
    esp_ota_handle_t handle = 0; bool opened = false;
    const char *failure = "flash_begin", *http_status = "500 Internal Server Error";
    mbedtls_sha256_context hash; mbedtls_sha256_init(&hash);
    if (mbedtls_sha256_starts(&hash,0) != 0) goto failed;
    if (esp_ota_begin(target,req->content_len,&handle) != ESP_OK) goto failed;
    opened = true; failure = "flash_write";
    if (esp_ota_write(handle,prefix,sizeof(prefix)) != ESP_OK ||
        mbedtls_sha256_update(&hash,prefix,sizeof(prefix)) != 0) goto failed;
    size_t remaining = req->content_len-sizeof(prefix);
    while (remaining) {
        size_t n = remaining > OTA_CHUNK ? OTA_CHUNK : remaining;
        if (!receive(req,chunk,n,deadline)) { failure="incomplete_upload"; http_status="408 Request Timeout"; goto failed; }
        if (esp_ota_write(handle,chunk,n) != ESP_OK || mbedtls_sha256_update(&hash,chunk,n) != 0) goto failed;
        remaining -= n;
    }
    uint8_t actual[32];
    if (mbedtls_sha256_finish(&hash,actual) != 0) goto failed;
    if (memcmp(actual,expected,sizeof(actual))) { failure="sha256_mismatch"; http_status="400 Bad Request"; goto failed; }
    esp_err_t end = esp_ota_end(handle); opened = false; // end always releases handle.
    if (end != ESP_OK) { failure="image_validation"; http_status="400 Bad Request"; goto failed; }
    // Prepare the restart resource before committing a new boot target.
    const esp_timer_create_args_t args = {.callback=restart,.name="ota-reboot"};
    if (esp_timer_create(&args,&reboot_timer) != ESP_OK) { failure="reboot_timer"; goto failed; }
    if (esp_ota_set_boot_partition(target) != ESP_OK) {
        esp_timer_delete(reboot_timer); reboot_timer=NULL; failure="boot_commit"; goto failed;
    }
    reboot_pending = true;
    mbedtls_sha256_free(&hash); free(chunk);
    // Keep maintenance latched until reset even if the success response is lost.
    ESP_ERROR_CHECK(esp_timer_start_once(reboot_timer,1500000));
    return json_reply(req,"202 Accepted","{\"accepted\":true,\"rebooting\":true}");

failed:
    if (opened) esp_ota_abort(handle);
    mbedtls_sha256_free(&hash); free(chunk); hardware_maintenance_end();
    return reject(req,http_status,failure);
}

void ota_register(httpd_handle_t server, const char *device, const char *boot) {
    device_id = device; boot_id = boot;
    const httpd_uri_t routes[] = {
        {.uri="/v2/ota",.method=HTTP_GET,.handler=status},
        {.uri="/v2/ota",.method=HTTP_POST,.handler=upload}
    };
    for (size_t i=0; i < sizeof(routes)/sizeof(routes[0]); ++i)
        ESP_ERROR_CHECK(httpd_register_uri_handler(server,&routes[i]));
}
