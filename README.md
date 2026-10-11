# Send Anyway

Send files and text between two Android phones by whatever channel works:
**Wi-Fi/LAN → Bluetooth → QR slideshow.** Pairing is always a QR code.

- Kotlin, Android framework APIs only (no AndroidX, no Compose, no CameraX, no coroutines)
- One runtime library: [`com.google.zxing:core`](https://github.com/zxing/zxing) for QR encode/decode
- minSdk 24 (Android 7.0), targetSdk 35. Every transport works on all supported versions.
- Release APK is ~100 KB

## How it works

1. **Sender** picks files / text (or shares into the app) and shows a **pairing QR code**.
   It starts listening for connections on every available channel at the same time.
2. **Receiver** scans it. It now knows the session key and how to reach the sender, and tries
   every channel in parallel. The first connection that proves it knows the key wins.
3. If nothing connects, the sender taps **"Send as QR slideshow"**. The receiver's camera is still
   running and picks up the frames automatically. No network, no radio, no permissions besides camera.

| Channel | How the receiver finds the sender | Data path |
|---|---|---|
| Wi-Fi / LAN | IPv4 addresses + port inside the pairing QR | TCP |
| Wi-Fi Direct | Sender taps **Start Wi-Fi Direct** and owns a P2P group (Android 10+: app-chosen name/password, pinned to 2.4 GHz); name + password go into the QR. Receiver (Android 10+) joins with `WifiP2pManager.connect(WifiP2pConfig(networkName, passphrase))`, no dialog. Fallback on any version: join `DIRECT-…` in Wi-Fi settings, a network watcher connects | TCP to group owner 192.168.49.1 |
| Bluetooth (RFCOMM) | Sender asks to become discoverable (system prompt) and listens on a per-session RFCOMM UUID; receiver runs classic discovery and tries phones strongest-signal first (then previously paired ones) | Insecure RFCOMM |
| QR slideshow | — (frames are self-describing) | Camera, one-way |

Scanner: continuous AF finds the code, then focus is **locked** as soon as a code decodes (no lens "breathing"
between frames); it unlocks after 2.5 s without a decode or on tap. Optical stabilisation is enabled when available,
and AE runs at a fixed ~30 fps to keep exposures short.

When the code includes a Wi-Fi Direct group: shared LAN and Wi-Fi Direct start together; Bluetooth only if Wi-Fi Direct fails.

Flow control: the receiver ACKs every 32 KB and the sender keeps ≤ 256 KB in flight, so progress on both sides is real.
A transfer with no progress for 20 s is aborted with a clear message.

Apps can't read their own Bluetooth MAC address (since Android 6), so the receiver finds the sender by
classic discovery: the sender asks to become visible, listens on a per-session RFCOMM UUID, and only it
accepts that UUID, so other phones are rejected quickly. Insecure RFCOMM avoids the pairing dialog on most phones.

## Security

The pairing QR carries a random 32-byte key. Every frame on LAN/Bluetooth is AES-256-GCM encrypted.
The IV is an implicit direction+counter (never reused, no replay or reorder within a connection) and the
session id is bound in as AAD. A peer without the key fails on the first frame, and that is how both
sides authenticate each other. The QR slideshow is not encrypted: it's line-of-sight only.

## Project layout

```
app/src/main/java/x/vladgba/sendanyway/
├── core/            pure Kotlin, no Android imports, unit-tested
│   ├── Wire.kt          QR magic/type bytes, string helpers
│   ├── Pairing.kt       pairing QR payload (binary)
│   ├── SecureChannel.kt AES-GCM framed stream
│   ├── Protocol.kt      HELLO / OFFER / ACCEPT / DATA / ITEM_END / DONE
│   ├── Items.kt         ItemInfo, OutgoingItem, ReceiveSink
│   └── QrTransfer.kt    QrBundle (deflate), QrSlicer, QrAssembler
├── transport/       Lan.kt (TCP), Classic.kt (Bluetooth RFCOMM), Bluetooth.kt (permissions), WifiDirect.kt (P2P group + manual-join watcher)
├── qr/              QrRender (ZXing → Bitmap), QrScanner (camera2 → ZXing)
├── data/            UriItem (content:// input), DownloadSink (→ Download/SendAnyway)
└── ui/              MainActivity, SendActivity, ReceiveActivity
```

## Wire formats

**Pairing QR** — `A5 01 ver | sessionId[8] | key[32] | flags | name | port u16 | n | (len, addr)… | [groupName, passphrase]`
(flags: bit0 Bluetooth RFCOMM, bit1 Wi-Fi Direct group)

**Slideshow frame** — `A5 02 | crc32 u32 | bundleLen u32 | chunkSize u16 | seq u32 | bytes…`
`seq < K` is source chunk `seq`; `seq ≥ K` is a repair chunk: XOR of 2–32 source chunks picked from (seq, crc) by a shared PRNG. After one full pass the sender alternates source and repair frames; the receiver rebuilds missing chunks by peeling (any repair with one unknown chunk reveals it). Every frame is decoded in memory before display and re-encoded with another QR mask if ZXing can't read it.
The bundle is DEFLATE-compressed: `count u16`, then for each item `ItemInfo` + raw bytes.

**Stream protocol** (inside `SecureChannel` frames):

```
receiver                         sender
  HELLO name          ──▶
                      ◀──        OFFER name, items[]
  ACCEPT              ──▶
                      ◀──        DATA… ITEM_END   (per item)
  ACK bytes           ──▶        (every 32 KB)
                      ◀──        DONE
  DONE (ack)          ──▶
```

## Build

Open the folder in Android Studio, or:

```
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # protocol, crypto, QR round-trip tests
```

## Status / next steps

- [x] Core protocol, encryption, QR slideshow codec (unit-tested)
- [x] LAN/Wi-Fi transport, camera2 scanner, send/receive screens, share-sheet target
- [x] Wi-Fi Direct group on the sender (2.4 GHz, app-chosen credentials) + in-app join on the receiver; replaced LocalOnlyHotspot, whose scan-based join dialog often found nothing
- [x] ACK flow control, stall watchdog, speed readout
- [x] Camera focus lock / OIS / fixed AE fps for steadier scanning
- [x] Fountain-coded slideshow (systematic XOR repair frames + peeling decoder), chunk map on the receiver, QR mask self-check
- [x] Bluetooth via RFCOMM
- [ ] Foreground service so large transfers survive leaving the screen
