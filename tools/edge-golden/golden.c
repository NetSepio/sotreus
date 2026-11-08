// Emits golden Edge records as hex lines, built from the firmware's own packed structs, for
// edge/protocol/src/test/resources/golden-records.txt. Build from the repo root:
//   E=../sotreus-edge
//   cc -std=c11 -I$E/tests/host/stub -I$E/main -I$E/managed_components/espressif__cjson/cJSON \
//      tools/edge-golden/golden.c -lz -o /tmp/edge-golden && /tmp/edge-golden
#include <stdio.h>
#include <string.h>
#include <stddef.h>
#include <zlib.h>
#include "edge_log.h"
#include "edge_records.h"

static void emit(const char *tag, edge_rec_t *r) {
    r->format = EDGE_REC_FORMAT;
    r->crc = (uint32_t)crc32(0L, (const Bytef *)r, offsetof(edge_rec_t, crc));
    const uint8_t *p = (const uint8_t *)r;
    printf("%s ", tag);
    for (size_t i = 0; i < sizeof(*r); i++) printf("%02X", p[i]);
    printf("\n");
}

int main(void) {
    edge_rec_t r;
    memset(&r, 0, sizeof r);
    r.seq = 0x01020304; r.type = EDGE_REC_BLE; r.flags = EDGE_RECF_TIME_VALID | EDGE_RECF_CONTINUES; r.boot = 0xBEEF;
    r.first_ms = 1762400000123LL; r.last_ms = 1762400300456LL;
    edge_body_ble_t *b = (edge_body_ble_t *)r.body;
    uint8_t a[6] = {0xC4, 0x5B, 0xBE, 0x01, 0x02, 0x03};
    memcpy(b->addr, a, 6); b->addr_type = 1; b->evt_type = 3; b->rssi_last = -67; b->rssi_max = -52; b->rssi_min = -90;
    b->flags = EDGE_BLEF_SCAN_RSP | EDGE_BLEF_CONNECTABLE; b->count = 70000;
    uint8_t adv[] = {0x02, 0x01, 0x06, 0x05, 0x09, 'T', 'a', 'g', '1', 0x03, 0xFF, 0x4C, 0x00};
    b->adv_len = sizeof adv; memcpy(b->adv, adv, sizeof adv);
    uint8_t rsp[] = {0x03, 0x03, 0xAA, 0xFE};
    b->rsp_len = sizeof rsp; memcpy(b->rsp, rsp, sizeof rsp);
    r.body_len = sizeof(*b);
    emit("BLE", &r);

    memset(&r, 0, sizeof r);
    r.seq = 77; r.type = EDGE_REC_WIFI_AP; r.flags = 0; r.boot = 12; r.first_ms = 5000; r.last_ms = 65000;
    edge_body_wifi_ap_t *w = (edge_body_wifi_ap_t *)r.body;
    uint8_t bs[6] = {0x24, 0x2F, 0xD0, 0xBD, 0x0F, 0x10};
    memcpy(w->bssid, bs, 6); w->channel = 11; w->rssi_last = -72; w->rssi_max = -70; w->rssi_min = -76;
    w->auth = EDGE_AUTH_WPA2_WPA3; w->flags = EDGE_APF_REMOTE_ID; w->count = 30; w->beacon_itvl = 100;
    const char *ssid = "Caf\xC3\xA9 Net"; w->ssid_len = strlen(ssid); memcpy(w->ssid, ssid, w->ssid_len);
    uint8_t rid[] = {0xFA, 0x0B, 0xBC, 0x0D, 0x01};
    w->rid_len = sizeof rid; memcpy(w->rid, rid, sizeof rid);
    r.body_len = sizeof(*w);
    emit("AP", &r);

    memset(&r, 0, sizeof r);
    r.seq = 4294967295u; r.type = EDGE_REC_WIFI_PROBE; r.flags = EDGE_RECF_TIME_VALID; r.boot = 1; r.first_ms = 1762400000000LL; r.last_ms = 1762400000000LL;
    edge_body_wifi_probe_t *pr = (edge_body_wifi_probe_t *)r.body;
    uint8_t m[6] = {0xCA, 0x2A, 0xF0, 0xE6, 0xB6, 0x01};
    memcpy(pr->mac, m, 6); pr->channel = 1; pr->rssi_last = -52; pr->rssi_max = -44; pr->rssi_min = -60;
    pr->flags = EDGE_PRBF_RANDOMIZED | EDGE_PRBF_PLAIN | EDGE_PRBF_MORE; pr->n_ssids = 2; pr->count = 4;
    pr->ssid_hash[0] = 0x9A1B2C3D; pr->ssid_hash[1] = 0xE40C292C;
    pr->plain[0].len = 4; memcpy(pr->plain[0].ssid, "Home", 4);
    pr->plain[1].len = 1; memcpy(pr->plain[1].ssid, "a", 1);
    r.body_len = sizeof(*pr);
    emit("PROBE", &r);

    memset(&r, 0, sizeof r);
    r.seq = 9; r.type = EDGE_REC_EVENT; r.flags = EDGE_RECF_TIME_VALID; r.boot = 3; r.first_ms = 1762400000000LL; r.last_ms = 1762400000000LL;
    edge_body_event_t *e = (edge_body_event_t *)r.body;
    e->code = EDGE_EVT_MODE; const char *d = "awareness"; e->detail_len = strlen(d); memcpy(e->detail, d, e->detail_len);
    r.body_len = 3 + e->detail_len;
    emit("EVENT", &r);
    return 0;
}
