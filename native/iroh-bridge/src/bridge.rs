//! Endpoints, connections and streams, each known by a number.
//!
//! Every endpoint owns its tokio runtime. A stream has a task reading its frames (a length of 4
//! bytes, big endian, then the bytes) into a queue and a task writing the frames of another
//! queue: [receive] and [send] only wait on the queues, so that a timeout never leaves half a
//! frame read or written.
//!
//! Anyone knowing the identifier of an endpoint can connect to it: the reader takes ahead only
//! the frames as large as the last [receive] allows (a device not known yet sends small ones),
//! in parts as their bytes come; an endpoint takes [MAX_INCOMING] connections at once, with
//! [RECEIVE_WINDOW] bytes in flight each.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::Duration;

use iroh::endpoint::{Connection, QuicTransportConfig, RecvStream, SendStream, VarInt, presets};
use iroh::{Endpoint, RelayMap, RelayMode, RelayUrl, SecretKey};
use tokio::runtime::Runtime;
use tokio::sync::{mpsc, watch};
use tokio::task::AbortHandle;

use crate::address;

/// The largest frame accepted, as on the local network.
pub const MAX_FRAME: usize = 256 * 1024 * 1024;

/// Frames waiting in each direction of a stream.
const QUEUE: usize = 8;

/// The first part of a frame read (see [read_frame]).
const FIRST_PART: usize = 64 * 1024;

/// The connections of other endpoints an endpoint keeps at once (open, given or not to the
/// application): the next ones are closed as soon as they are open.
const MAX_INCOMING: usize = 32;

/// The bytes another endpoint may send on a connection before this one reads them (all of its
/// streams together): 40 MB/s at 100 ms of round trip.
const RECEIVE_WINDOW: u32 = 4 * 1024 * 1024;

/// How long closing an endpoint waits for its connections to say goodbye.
const CLOSE_TIMEOUT: Duration = Duration::from_secs(2);

/// How long opening a stream may wait (the other endpoint limits the streams open at once).
const OPEN_TIMEOUT: Duration = Duration::from_secs(10);

/// Why a call did not do what it was asked.
#[derive(Debug)]
pub enum Failure {
    /// Nothing came before the timeout: the call may be made again.
    Timeout,
    /// The endpoint, connection or stream is closed (or the number is not one of them).
    Closed,
    /// Anything else, described.
    Error(String),
}

pub type Result<T> = std::result::Result<T, Failure>;

fn error(context: &str, e: impl std::fmt::Display) -> Failure {
    Failure::Error(format!("{context}: {e}"))
}

// =============================================================================
// Numbers
// =============================================================================

struct EndpointEntry {
    runtime: Option<Runtime>,
    endpoint: Endpoint,
    alpn: Vec<u8>,
    relays: bool,
    accepted: tokio::sync::Mutex<mpsc::Receiver<(Connection, IncomingSlot)>>,
}

/// A connection of another endpoint counted against [MAX_INCOMING], as long as it lives.
struct IncomingSlot(Arc<AtomicUsize>);

impl Drop for IncomingSlot {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::AcqRel);
    }
}

impl EndpointEntry {
    fn runtime(&self) -> &Runtime {
        self.runtime.as_ref().expect("runtime")
    }
}

impl Drop for EndpointEntry {
    fn drop(&mut self) {
        // Never on a thread of the runtime itself: the tasks hold no entry
        if let Some(runtime) = self.runtime.take() {
            runtime.shutdown_background();
        }
    }
}

struct ConnectionEntry {
    owner: Arc<EndpointEntry>,
    connection: Connection,
    streams: tokio::sync::Mutex<mpsc::Receiver<(SendStream, RecvStream)>>,
    /// Of a connection another endpoint opened.
    _slot: Option<IncomingSlot>,
}

struct StreamEntry {
    owner: Arc<EndpointEntry>,
    outgoing: Mutex<Option<mpsc::Sender<Vec<u8>>>>,
    incoming: tokio::sync::Mutex<mpsc::Receiver<Vec<u8>>>,
    /// The largest frame the reader takes ahead, from the last [receive].
    limit: watch::Sender<usize>,
    /// The length of the frame the reader holds back, larger than [limit] (0: none).
    held: Arc<AtomicUsize>,
    /// Set by the [receive] that found the stream ended.
    ended: AtomicBool,
    reader: AbortHandle,
}

enum Entry {
    Endpoint(Arc<EndpointEntry>),
    Connection(Arc<ConnectionEntry>),
    Stream(Arc<StreamEntry>),
}

static ENTRIES: LazyLock<Mutex<HashMap<u64, Entry>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT: AtomicU64 = AtomicU64::new(1);

fn register(entry: Entry) -> u64 {
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    ENTRIES.lock().unwrap().insert(id, entry);
    id
}

fn endpoint(id: u64) -> Result<Arc<EndpointEntry>> {
    match ENTRIES.lock().unwrap().get(&id) {
        Some(Entry::Endpoint(entry)) => Ok(entry.clone()),
        _ => Err(Failure::Closed),
    }
}

fn connection(id: u64) -> Result<Arc<ConnectionEntry>> {
    match ENTRIES.lock().unwrap().get(&id) {
        Some(Entry::Connection(entry)) => Ok(entry.clone()),
        _ => Err(Failure::Closed),
    }
}

fn stream(id: u64) -> Result<Arc<StreamEntry>> {
    match ENTRIES.lock().unwrap().get(&id) {
        Some(Entry::Stream(entry)) => Ok(entry.clone()),
        _ => Err(Failure::Closed),
    }
}

fn remove(id: u64) -> Option<Entry> {
    ENTRIES.lock().unwrap().remove(&id)
}

fn wait<T>(runtime: &Runtime, timeout_ms: i64, future: impl Future<Output = T>) -> Result<T> {
    let timeout = Duration::from_millis(timeout_ms.max(0) as u64);
    runtime.block_on(async { tokio::time::timeout(timeout, future).await }).map_err(|_| Failure::Timeout)
}

// =============================================================================
// Endpoints
// =============================================================================

/// The relays of [relays]: their addresses separated by spaces or lines, `n0` for the public
/// relays of n0 (development), nothing for none.
fn relay_mode(relays: &str, token: &str) -> Result<RelayMode> {
    let words: Vec<&str> = relays.split_whitespace().collect();
    if words.is_empty() {
        return Ok(RelayMode::Disabled);
    }
    if words == ["n0"] {
        return Ok(RelayMode::Default);
    }
    let urls = words
        .iter()
        .map(|word| word.parse::<RelayUrl>().map_err(|e| error(&format!("relay {word}"), e)))
        .collect::<Result<Vec<_>>>()?;
    let mut map = RelayMap::from_iter(urls);
    if !token.is_empty() {
        map = map.with_auth_token(token);
    }
    Ok(RelayMode::Custom(map))
}

/// Binds an endpoint with [secret_key] (32 bytes), through [relays] (see [relay_mode]) with the
/// access [token] of the relays (empty: none), answering the connections of [alpn].
pub fn bind(secret_key: &[u8], relays: &str, token: &str, alpn: &str) -> Result<u64> {
    let key: [u8; 32] = secret_key.try_into().map_err(|_| Failure::Error("a secret key of 32 bytes".into()))?;
    let mode = relay_mode(relays, token)?;
    let has_relays = !matches!(mode, RelayMode::Disabled);
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("iroh")
        .enable_all()
        .build()
        .map_err(|e| error("runtime", e))?;
    let transport = QuicTransportConfig::builder().receive_window(VarInt::from_u32(RECEIVE_WINDOW)).build();
    let endpoint = runtime
        .block_on(
            Endpoint::builder(presets::Minimal)
                .secret_key(SecretKey::from_bytes(&key))
                .alpns(vec![alpn.as_bytes().to_vec()])
                .relay_mode(mode)
                .transport_config(transport)
                .bind(),
        )
        .map_err(|e| error("bind", e))?;

    // The connections of the other endpoints, as their handshakes end
    let (accepted, receiver) = mpsc::channel(MAX_INCOMING);
    let listener = endpoint.clone();
    let count = Arc::new(AtomicUsize::new(0));
    runtime.spawn(async move {
        while let Some(incoming) = listener.accept().await {
            let accepted = accepted.clone();
            let count = count.clone();
            tokio::spawn(async move {
                let Ok(connection) = incoming.await else {
                    return;
                };
                // Too many open at once: closed. Counted once open (refusing the handshake would
                // refuse the other attempts of the same connection too)
                if count.fetch_add(1, Ordering::AcqRel) >= MAX_INCOMING {
                    count.fetch_sub(1, Ordering::AcqRel);
                    connection.close(1u32.into(), b"too many connections");
                    return;
                }
                let _ = accepted.send((connection, IncomingSlot(count))).await;
            });
        }
    });

    Ok(register(Entry::Endpoint(Arc::new(EndpointEntry {
        runtime: Some(runtime),
        endpoint,
        alpn: alpn.as_bytes().to_vec(),
        relays: has_relays,
        accepted: tokio::sync::Mutex::new(receiver),
    }))))
}

/// The address of the endpoint (see [address]), once it reached its home relay (at most
/// [timeout_ms]; at once without relays).
pub fn address(endpoint_id: u64, timeout_ms: i64) -> Result<String> {
    let entry = endpoint(endpoint_id)?;
    if entry.relays {
        let _ = wait(entry.runtime(), timeout_ms, entry.endpoint.online());
    }
    Ok(address::format(&entry.endpoint.addr()))
}

/// The identifier of the endpoint (its public key, in hexadecimal).
pub fn id(endpoint_id: u64) -> Result<String> {
    Ok(endpoint(endpoint_id)?.endpoint.id().to_string())
}

/// Tells the endpoint that the network changed (after the background, a change of Wi-Fi...).
pub fn network_change(endpoint_id: u64) -> Result<()> {
    let entry = endpoint(endpoint_id)?;
    entry.runtime().block_on(entry.endpoint.network_change());
    Ok(())
}

/// Closes the endpoint and its connections: its number is refused at once, the connections say
/// goodbye on a thread of their own (the caller does not wait).
pub fn close_endpoint(endpoint_id: u64) {
    if let Some(Entry::Endpoint(entry)) = remove(endpoint_id) {
        std::thread::spawn(move || {
            let _ = wait(entry.runtime(), CLOSE_TIMEOUT.as_millis() as i64, entry.endpoint.close());
        });
    }
}

// =============================================================================
// Connections
// =============================================================================

fn register_connection(owner: Arc<EndpointEntry>, connection: Connection, slot: Option<IncomingSlot>) -> u64 {
    // The streams the other endpoint opens
    let (streams, receiver) = mpsc::channel(QUEUE);
    let source = connection.clone();
    owner.runtime().spawn(async move {
        while let Ok(stream) = source.accept_bi().await {
            if streams.send(stream).await.is_err() {
                break;
            }
        }
    });
    register(Entry::Connection(Arc::new(ConnectionEntry {
        owner,
        connection,
        streams: tokio::sync::Mutex::new(receiver),
        _slot: slot,
    })))
}

/// Connects to the endpoint at [address] (see [address::format]).
pub fn connect(endpoint_id: u64, address: &str, timeout_ms: i64) -> Result<u64> {
    let entry = endpoint(endpoint_id)?;
    let addr = address::parse(address).ok_or_else(|| Failure::Error(format!("not an address: {address}")))?;
    let connection = wait(entry.runtime(), timeout_ms, entry.endpoint.connect(addr, &entry.alpn))?
        .map_err(|e| error("connect", e))?;
    Ok(register_connection(entry, connection, None))
}

/// The next connection another endpoint opened to this one.
pub fn accept(endpoint_id: u64, timeout_ms: i64) -> Result<u64> {
    let entry = endpoint(endpoint_id)?;
    let (connection, slot) = wait(entry.runtime(), timeout_ms, async { entry.accepted.lock().await.recv().await })?
        .ok_or(Failure::Closed)?;
    Ok(register_connection(entry, connection, Some(slot)))
}

/// The identifier of the endpoint at the other end.
pub fn remote_id(connection_id: u64) -> Result<String> {
    Ok(connection(connection_id)?.connection.remote_id().to_string())
}

/// What the connection goes through now: 0 nothing yet, 1 a direct path, 2 a relay; and its
/// round-trip time in milliseconds.
pub fn path(connection_id: u64) -> Result<(i32, i64)> {
    let entry = connection(connection_id)?;
    for path in entry.connection.paths().iter() {
        if path.is_selected() {
            let kind = if path.is_relay() { 2 } else { 1 };
            return Ok((kind, path.rtt().as_millis() as i64));
        }
    }
    Ok((0, 0))
}

/// Closes the connection and its streams.
pub fn close_connection(connection_id: u64) {
    if let Some(Entry::Connection(entry)) = remove(connection_id) {
        entry.connection.close(0u32.into(), b"closed");
    }
}

// =============================================================================
// Streams
// =============================================================================

fn register_stream(owner: Arc<EndpointEntry>, mut send: SendStream, mut recv: RecvStream) -> u64 {
    let runtime = owner.runtime();
    let (outgoing, mut to_write) = mpsc::channel::<Vec<u8>>(QUEUE);
    runtime.spawn(async move {
        while let Some(frame) = to_write.recv().await {
            let length = (frame.len() as u32).to_be_bytes();
            if send.write_all(&length).await.is_err() || send.write_all(&frame).await.is_err() {
                return;
            }
        }
        // Closed by this side: the frames queued are written first
        let _ = send.finish();
    });

    let (read, incoming) = mpsc::channel::<Vec<u8>>(QUEUE);
    // Nothing is taken ahead before the first receive says how large
    let (limit, mut allowed) = watch::channel(0usize);
    let held = Arc::new(AtomicUsize::new(0));
    let holding = held.clone();
    let reader = runtime.spawn(async move {
        loop {
            let mut length = [0u8; 4];
            if recv.read_exact(&mut length).await.is_err() {
                break;
            }
            let length = u32::from_be_bytes(length) as usize;
            if length > MAX_FRAME {
                break;
            }
            // Held back until a receive takes frames this large (it fails the stream otherwise)
            if length > *allowed.borrow_and_update() {
                holding.store(length, Ordering::Release);
                while length > *allowed.borrow_and_update() {
                    if allowed.changed().await.is_err() {
                        return;
                    }
                }
                holding.store(0, Ordering::Release);
            }
            let Some(frame) = read_frame(&mut recv, length).await else {
                break;
            };
            if read.send(frame).await.is_err() {
                break;
            }
        }
        // The queue closes: the frames in it are received, then the end
    });

    register(Entry::Stream(Arc::new(StreamEntry {
        owner,
        outgoing: Mutex::new(Some(outgoing)),
        incoming: tokio::sync::Mutex::new(incoming),
        limit,
        held,
        ended: AtomicBool::new(false),
        reader: reader.abort_handle(),
    })))
}

/// The [length] bytes of a frame of [recv], in parts each as large as what came before it: the
/// memory follows the bytes that come, not the length announced. `None` when the stream ends
/// first.
async fn read_frame(recv: &mut RecvStream, length: usize) -> Option<Vec<u8>> {
    let mut frame = vec![0u8; length.min(FIRST_PART)];
    let mut read = 0;
    loop {
        recv.read_exact(&mut frame[read..]).await.ok()?;
        read = frame.len();
        if read == length {
            return Some(frame);
        }
        frame.resize(length.min(read * 2), 0);
    }
}

/// Opens a stream to the other endpoint (it sees it once the first frame is sent).
pub fn open_stream(connection_id: u64) -> Result<u64> {
    let entry = connection(connection_id)?;
    let (send, recv) = wait(entry.owner.runtime(), OPEN_TIMEOUT.as_millis() as i64, entry.connection.open_bi())?
        .map_err(|e| error("open stream", e))?;
    Ok(register_stream(entry.owner.clone(), send, recv))
}

/// The next stream the other endpoint opened.
pub fn accept_stream(connection_id: u64, timeout_ms: i64) -> Result<u64> {
    let entry = connection(connection_id)?;
    let (send, recv) = wait(entry.owner.runtime(), timeout_ms, async { entry.streams.lock().await.recv().await })?
        .ok_or(Failure::Closed)?;
    Ok(register_stream(entry.owner.clone(), send, recv))
}

/// Queues [frame] to be sent; [Failure::Timeout] when the queue stayed full (the frame is not
/// queued).
pub fn send(stream_id: u64, frame: Vec<u8>, timeout_ms: i64) -> Result<()> {
    let entry = stream(stream_id)?;
    if frame.len() > MAX_FRAME {
        return Err(Failure::Error(format!("a frame of {} bytes", frame.len())));
    }
    let outgoing = entry.outgoing.lock().unwrap().clone().ok_or(Failure::Closed)?;
    wait(entry.owner.runtime(), timeout_ms, outgoing.send(frame))?.map_err(|_| Failure::Closed)
}

/// The next frame of the other endpoint, of [max_size] bytes at most: a larger one fails the
/// stream unread ([Failure::Error], then [is_ended]). [Failure::Closed] once the stream ended
/// (every frame received before is given first).
pub fn receive(stream_id: u64, timeout_ms: i64, max_size: usize) -> Result<Vec<u8>> {
    let entry = stream(stream_id)?;
    // The reader takes ahead the frames this large
    entry.limit.send_if_modified(|limit| std::mem::replace(limit, max_size) != max_size);
    let too_large = |size: usize| {
        entry.ended.store(true, Ordering::Release);
        entry.reader.abort();
        Failure::Error(format!("a frame of {size} bytes"))
    };
    let held = entry.held.load(Ordering::Acquire);
    if held > max_size {
        return Err(too_large(held));
    }
    match wait(entry.owner.runtime(), timeout_ms, async { entry.incoming.lock().await.recv().await })? {
        Some(frame) if frame.len() > max_size => Err(too_large(frame.len())),
        Some(frame) => Ok(frame),
        None => {
            entry.ended.store(true, Ordering::Release);
            Err(Failure::Closed)
        }
    }
}

/// Whether a [receive] found the stream ended (or the stream is closed): what tells an ended
/// stream from a timeout where only a missing frame can be returned (JNI).
pub fn is_ended(stream_id: u64) -> bool {
    stream(stream_id).map(|entry| entry.ended.load(Ordering::Acquire)).unwrap_or(true)
}

/// Closes the stream: the frames queued are sent, then the stream is finished; nothing more is
/// read.
pub fn close_stream(stream_id: u64) {
    if let Some(Entry::Stream(entry)) = remove(stream_id) {
        entry.outgoing.lock().unwrap().take();
        entry.reader.abort();
    }
}
