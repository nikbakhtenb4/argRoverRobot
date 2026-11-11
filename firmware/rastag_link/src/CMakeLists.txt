idf_component_register(SRCS "main.c" "link_protocol.c" "b0602.c" "lidar_wire.c" "arg_rover.c" "hardware_runtime.c" "ota_update.c"
    INCLUDE_DIRS "."
    REQUIRES esp_wifi esp_event esp_netif nvs_flash esp_https_server json esp_timer esp_driver_uart esp_driver_gpio esp_driver_ledc esp_driver_dac mbedtls app_update esp_app_format bootloader_support)

