/*
 * The C API of the iroh bridge of DrawView (native/iroh-bridge/src/ffi.rs), read by cinterop on
 * iOS. Endpoints, connections and streams are numbers: a number closed or never given is
 * refused (DV_IROH_CLOSED).
 *
 * Every function returning int32_t gives DV_IROH_OK, DV_IROH_TIMEOUT, DV_IROH_CLOSED or
 * DV_IROH_ERROR (described by dv_iroh_last_error, on the same thread).
 */
#ifndef IROH_BRIDGE_H
#define IROH_BRIDGE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define DV_IROH_OK 0
#define DV_IROH_TIMEOUT 1
#define DV_IROH_CLOSED 2
#define DV_IROH_ERROR (-1)

/* Bytes given by the bridge, freed with dv_iroh_bytes_free. */
typedef struct {
    uint8_t *data;
    size_t length;
} DvIrohBytes;

/* The message of the last DV_IROH_ERROR of this thread, valid until its next call. */
const char *dv_iroh_last_error(void);

/*
 * Binds an endpoint with a secret key of 32 bytes, through relays (their addresses separated by
 * spaces, "n0" for the public relays of n0, "" for none) with their access token ("" for none),
 * answering the connections of alpn.
 */
int32_t dv_iroh_bind(const uint8_t *secret_key, size_t secret_key_length, const char *relays,
                     const char *token, const char *alpn, uint64_t *endpoint);
/* Its address (freed with dv_iroh_string_free), once it reached its home relay. */
int32_t dv_iroh_address(uint64_t endpoint, int64_t timeout_ms, char **address);
/* Its identifier, the public key in hexadecimal (freed with dv_iroh_string_free). */
int32_t dv_iroh_id(uint64_t endpoint, char **id);
/* Tells the endpoint the network changed. */
int32_t dv_iroh_network_change(uint64_t endpoint);
void dv_iroh_close_endpoint(uint64_t endpoint);

int32_t dv_iroh_connect(uint64_t endpoint, const char *address, int64_t timeout_ms, uint64_t *connection);
int32_t dv_iroh_accept(uint64_t endpoint, int64_t timeout_ms, uint64_t *connection);
int32_t dv_iroh_remote_id(uint64_t connection, char **id);
/* What the connection goes through: kind 0 nothing yet, 1 direct, 2 relay; its round-trip time. */
int32_t dv_iroh_path(uint64_t connection, int32_t *kind, int64_t *rtt_ms);
void dv_iroh_close_connection(uint64_t connection);

int32_t dv_iroh_open_stream(uint64_t connection, uint64_t *stream);
int32_t dv_iroh_accept_stream(uint64_t connection, int64_t timeout_ms, uint64_t *stream);
/* Queues a frame; DV_IROH_TIMEOUT when the queue stayed full (the frame is not queued). */
int32_t dv_iroh_send(uint64_t stream, const uint8_t *frame, size_t length, int64_t timeout_ms);
/* The next frame of max_size bytes at most (freed with dv_iroh_bytes_free): a larger one fails
 * the stream unread (DV_IROH_ERROR, then dv_iroh_is_ended); DV_IROH_CLOSED once the stream ended. */
int32_t dv_iroh_receive(uint64_t stream, int64_t timeout_ms, size_t max_size, DvIrohBytes *frame);
/* Whether a dv_iroh_receive found the stream ended (or the stream is closed). */
bool dv_iroh_is_ended(uint64_t stream);
/* Sends the frames queued, finishes the stream and reads nothing more. */
void dv_iroh_close_stream(uint64_t stream);

void dv_iroh_string_free(char *value);
void dv_iroh_bytes_free(DvIrohBytes bytes);

#endif
