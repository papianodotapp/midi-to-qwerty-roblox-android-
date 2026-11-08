#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <linux/uinput.h>
#include <cerrno>
#include <fcntl.h>
#include <poll.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <termios.h>
#include <unistd.h>
#include <linux/uhid.h>
#include <linux/input.h>
#include <jni.h>
#include <android/log.h>
#include <unordered_map>
#include <string>
#include <array>
#include <vector>
#include <mutex>
#include <atomic>
#include <thread>
#include <algorithm>
#include <sys/resource.h>
#include <sys/syscall.h>

static int uhid_fd = -1;
static struct uhid_event uhidEvent;
const char *TAG = "RobloxAndroidMidi";

// NKRO report layout: byte0 = modifier bitmask, byte1 = reserved (always 0),
// bytes2.. = a 1-bit-per-usage-code bitmap (see NKRO_USAGE_MAX/NKRO_BITMAP_BYTES
// below and the `description` HID report descriptor). Replaces the old
// boot-protocol layout (1 modifier byte + up to 6 keycode slots), which
// capped simultaneous held keys at 6 — every held key now gets its own bit,
// so the cap is gone (bounded only by NKRO_USAGE_MAX, far above anything a
// human chord or sustained pedal ever needs).
static const int NKRO_USAGE_MAX = 103;                    // valid usage codes: 1..103 (0 = "no key")
static const int NKRO_BITMAP_BYTES = 13;                  // ceil(104 usage codes / 8)
static const int REPORT_SIZE = 2 + NKRO_BITMAP_BYTES;     // modifier + reserved + bitmap = 15

// Last HID report we sent to the kernel. Updated on every actual write so it
// always reflects the kernel's view, even when the press path issues its own
// direct writes (e.g. velocity Alt+velKey tap). emitHeldReport() consults this
// to skip syscalls that would push an already-current state. Each saved write
// is one less event for the kernel HID dispatcher and Roblox to chew through,
// which directly translates to lower felt latency on burst sections.
static uint8_t g_lastReport[REPORT_SIZE] = {0xFF};

// ============================================================================
// UHID write helper — retry on EAGAIN/EINTR.
// ----------------------------------------------------------------------------
// /dev/uhid was opened O_NONBLOCK (O_NDELAY). If the kernel report queue is
// momentarily full (rare burst case) write() returns -1 with errno=EAGAIN.
// Old code ignored the return value -> the note was silently dropped, which
// the user perceives as "missed a key" (much worse than a tiny extra wait).
//
// Strategy: spin-retry with sched_yield up to ~1ms total. Real-world UHID
// write should complete in microseconds; if it doesn't, we'd rather wait a
// few hundred microseconds than lose the note. Bounded so a truly broken fd
// can't hang the binder thread forever.
// ============================================================================
static inline ssize_t uhidWrite(const void *buf, size_t len) {
    if (uhid_fd < 0) return -1;
    // ~1ms total budget at 4us per yield iteration (256 attempts).
    for (int attempt = 0; attempt < 256; ++attempt) {
        ssize_t r = write(uhid_fd, buf, len);
        if (r >= 0) return r;
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            sched_yield();
            continue;
        }
        // Hard error: log once per uHID lifetime and bail.
        static std::atomic<bool> warned{false};
        bool expected = false;
        if (warned.compare_exchange_strong(expected, true)) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                    "uhid write failed: errno=%d (%s)", errno, strerror(errno));
        }
        return r;
    }
    return -1;
}

// Keyboard description.
//
// Modifier byte + reserved byte are the standard boot-protocol prefix
// (unchanged). The trailing section used to be a 6-slot keycode ARRAY
// (Input Data,Array — one of 101 values per byte, 6 bytes = 6 keys max
// concurrently). That's the USB boot-protocol 6KRO limit piano players hit
// on big chords / sustained notes.
//
// Replaced with an NKRO BITMAP: one Input Data,Var bit per usage code
// 0x00-0x67 (104 codes = 13 bytes). Every simultaneously-held key gets its
// own bit instead of competing for one of 6 slots — since this is a virtual
// device we fully control (not a real keyboard's fixed matrix), there's no
// reason to inherit the boot-protocol's HID-over-USB compatibility cap.
static unsigned char description[] = {
        0x05, 0x01,        // Usage Page (Generic Desktop Ctrls)
        0x09, 0x06,        // Usage (Keyboard)
        0xA1, 0x01,        // Collection (Application)
        0x05, 0x07,        //   Usage Page (Kbrd/Keypad)
        0x19, 0xE0,        //   Usage Minimum (0xE0)
        0x29, 0xE7,        //   Usage Maximum (0xE7)
        0x15, 0x00,        //   Logical Minimum (0)
        0x25, 0x01,        //   Logical Maximum (1)
        0x75, 0x01,        //   Report Size (1)
        0x95, 0x08,        //   Report Count (8)
        0x81, 0x02,        //   Input (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position) -- modifier byte
        0x95, 0x01,        //   Report Count (1)
        0x75, 0x08,        //   Report Size (8)
        0x81, 0x03,        //   Input (Const,Var,Abs,No Wrap,Linear,Preferred State,No Null Position) -- reserved byte
        0x95, 0x05,        //   Report Count (5)
        0x75, 0x01,        //   Report Size (1)
        0x05, 0x08,        //   Usage Page (LEDs)
        0x19, 0x01,        //   Usage Minimum (Num Lock)
        0x29, 0x05,        //   Usage Maximum (Kana)
        0x91, 0x02,        //   Output (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position,Non-volatile)
        0x95, 0x01,        //   Report Count (1)
        0x75, 0x03,        //   Report Size (3)
        0x91, 0x03,        //   Output (Const,Var,Abs,No Wrap,Linear,Preferred State,No Null Position,Non-volatile)
        0x05, 0x07,        //   Usage Page (Kbrd/Keypad)
        0x19, 0x00,        //   Usage Minimum (0x00)
        0x29, 0x67,        //   Usage Maximum (0x67) -- 104 usage codes (0..103)
        0x15, 0x00,        //   Logical Minimum (0)
        0x25, 0x01,        //   Logical Maximum (1)
        0x75, 0x01,        //   Report Size (1)
        0x95, 0x68,        //   Report Count (104)
        0x81, 0x02,        //   Input (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position) -- NKRO bitmap
        0xC0,              // End Collection

};

const int NUM_KEYS = 88;

// Sets the bit for a single HID usage code in an NKRO bitmap. code<=0 means
// "no key" (matches the old array format's 0 = empty slot convention, and
// usage 0x00 is reserved/"no event" in the HID Keyboard usage page anyway),
// so it's silently ignored. Codes above NKRO_USAGE_MAX are out of the
// bitmap's declared range and also ignored (none of our keycode tables emit
// anything above 0x62).
static inline void setBit(uint8_t* bitmap, int code) {
    if (code <= 0 || code > NKRO_USAGE_MAX) return;
    bitmap[code / 8] |= (uint8_t) (1 << (code % 8));
}

// Writes a full NKRO report (modifier byte + prebuilt bitmap) and updates
// g_lastReport. Callers that already have a bitmap in hand (emitHeldReport,
// tapVelocityKey) use this directly to avoid rebuilding it.
static void writeKeyboardBitmap(int metakey0, const uint8_t bitmap[NKRO_BITMAP_BYTES]) {
    uhidEvent.u.input.data[0] = metakey0;
    uhidEvent.u.input.data[1] = 0x00; // reserved
    memcpy(&uhidEvent.u.input.data[2], bitmap, NKRO_BITMAP_BYTES);
    uhidWrite(&uhidEvent, sizeof(uhidEvent));

    // Track what the kernel last received so emitHeldReport()'s dedup is
    // accurate even for direct writes (e.g. velocity Alt+velKey tap).
    g_lastReport[0] = (uint8_t) metakey0;
    g_lastReport[1] = 0x00;
    memcpy(&g_lastReport[2], bitmap, NKRO_BITMAP_BYTES);
}

// Builds a bitmap from a flat list of up-to-6 usage codes (0 = padding/no-key)
// and writes it. Used by nativeQwertyKey's meta-collision-fix path to force
// an empty report (all-zero args) so a shared key cycles up->down.
void writeKeyboard(int metakey0=0x00, int reserved1=0x00, int data2=0x00, int data3=0x00, int data4=0x00, int data5=0x00, int data6=0x00, int data7=0x00) {
    (void) reserved1; // always 0 at every call site; reserved byte is fixed 0x00 in the NKRO report
    const int codes[6] = {data2, data3, data4, data5, data6, data7};
    uint8_t bitmap[NKRO_BITMAP_BYTES] = {0};
    for (int code : codes) setBit(bitmap, code);
    writeKeyboardBitmap(metakey0, bitmap);
}

// ============================================================================
// QWERTY hold/sustain state
// ----------------------------------------------------------------------------
// Kita track semua note yang lagi di-hold di g_heldKeys, dan tiap kali ada
// press/release kita re-emit aggregated HID report dengan SEMUA held notes
// di-set jadi bit di NKRO bitmap — gak ada cap 6-key lagi (lihat description[]
// dan NKRO_USAGE_MAX/BITMAP_BYTES di atas). Chord/sustain sebesar apapun
// (dalam batas 88-key piano) kedenger utuh, gak ada not yang di-evict.
//
// meta byte tetep di-OR dari semua held keys (Roblox piano pake shift utk note
// hitam tertentu dan ctrl utk oktaf paling rendah/tinggi). Kalo user
// kombinasiin note dgn meta beda (mis. capital + ctrl_) hasil meta jadi
// gabungan — sayangnya HID cuma punya 1 byte modifier, ini limit fundamental
// yang gak related ke jumlah key (makanya tetep ditangani terpisah di
// nativeQwertyKey lewat "meta collision" clear, bukan lewat NKRO bitmap).
// ============================================================================

struct HeldKey {
    int note;   // MIDI note number (utk match release ke press yg bener)
    int meta;   // modifier byte (left ctrl=0x01, left shift=0x02, none=0x00)
    int hex;    // HID usage code
};

static std::vector<HeldKey> g_heldKeys;
static std::mutex g_heldMutex;

// Emit aggregated HID report dari current held set.
// Caller MUST hold g_heldMutex.
static void emitHeldReport() {
    int meta = 0;
    uint8_t bitmap[NKRO_BITMAP_BYTES] = {0};
    for (const auto& h : g_heldKeys) {
        setBit(bitmap, h.hex);
        meta |= h.meta;
    }
    // Same-state dedup against the actual last-written kernel report.
    // writeKeyboardBitmap() updates g_lastReport, so this also correctly
    // skips the case where tapVelocityKey just sent the equivalent transition.
    uint8_t next[REPORT_SIZE];
    next[0] = (uint8_t) meta;
    next[1] = 0x00;
    memcpy(&next[2], bitmap, NKRO_BITMAP_BYTES);
    if (memcmp(next, g_lastReport, REPORT_SIZE) == 0) return;
    writeKeyboardBitmap(meta, bitmap);
}


// ============================================================================
// VELOCITY ("Visual Piano" / MIDI++ style) — Alt + velocity-key, 32 steps
// ----------------------------------------------------------------------------
// Game piano tertentu (Visual Piano, Piano Gardens, dll) menerima velocity
// lewat keypress TERPISAH: tap `Alt + <key>` untuk SET level velocity, lalu
// notnya ditekan biasa. Game inget level itu sampai diganti.
//
// Skema diambil dari MIDI++ (Zephkek/MIDIPlusPlus): 32 level, dipetakan ke
// deret tombol di bawah (pelan -> keras). MIDI velocity 0..127 dipetakan
// linear ke index 0..31. velKey CUMA dikirim ulang saat level berubah
// (lihat g_lastVelHex) — biar gak spam keypress tiap not.
//
// LeftAlt = bit 2 di modifier byte = 0x04 (sejajar LeftCtrl=0x01, Shift=0x02).
// ============================================================================
static const int MOD_LEFTALT = 0x04;

// HID usage code utk tiap velocity-key, index 0..31:
//   1 2 3 4 5 6 7 8 9 0  q w e r t y u i o p  a s d f g h j k l  z x c
static const int g_velKeyHex[32] = {
    0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, // 1..0
    0x14, 0x1A, 0x08, 0x15, 0x17, 0x1C, 0x18, 0x0C, 0x12, 0x13, // q w e r t y u i o p
    0x04, 0x16, 0x07, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F,       // a s d f g h j k l
    0x1D, 0x1B, 0x06                                            // z x c
};

// velKey terakhir yg di-emit (-1 = belum ada). Reset di nativeCloseUHid.
static int g_lastVelHex = -1;

// MIDI velocity (1..127) -> velocity-key index (0..31), linear.
static inline int velocityToIndex(int velocity) {
    int idx = (velocity * 32) / 128;
    if (idx < 0) idx = 0;
    if (idx > 31) idx = 31;
    return idx;
}

// Kirim command "Alt + velKey" TANPA nge-release not yg lagi di-hold.
// Not yg di-hold dimasukin ke report yg sama biar tetep down (Roblox fire
// InputBegan cuma pas keycode transisi up->down; nambah modifier Alt
// sementara TIDAK nge-retrigger not yg udah down). velKey dapet transisi
// down->up fresh, jadi handler Alt+key velocity di game ke-trigger.
//
// NKRO bitmap = tiap held note dapet bit sendiri, jadi SEMUA held note ikut
// (dulu, waktu formatnya 6-slot array, cuma 5 muat sebarengan sama velKey —
// chord besar + velocity toggle ON bakal kepotong notnya). Kalau velHex
// kebetulan sama dgn hex salah satu held note, setBit ke bit yang sama itu
// idempotent (gak perlu skip manual kayak sebelumnya).
// Caller MUST hold g_heldMutex.
static void tapVelocityKey(int velHex) {
    uint8_t bitmap[NKRO_BITMAP_BYTES] = {0};
    for (const auto& h : g_heldKeys) setBit(bitmap, h.hex);
    setBit(bitmap, velHex);
    writeKeyboardBitmap(MOD_LEFTALT, bitmap);
    // restore: Alt + velKey dilepas, not yg di-hold tetep down
    emitHeldReport();
}


// ============================================================================
// ZERO-ALLOCATION LOOKUP TABLE for nativeQwertyKey hot path
// ----------------------------------------------------------------------------
// Precomputed from pianoQwertyKeys + qwerty_to_hex_map + meta_key_map.
// Index = noteNumber - 21 (0..87). Each entry: {hid_keycode, meta_byte}.
// Eliminates: std::string alloc, hash lookup x2 per note event.
// ============================================================================
struct KeyEntry { int hex; int meta; };

static const KeyEntry g_keyLookup[NUM_KEYS] = {
    // A0 (notes 21-35) — ctrl_ prefix = meta 0x01
    {0x1E, 0x01}, // ctrl_1
    {0x1F, 0x01}, // ctrl_2
    {0x20, 0x01}, // ctrl_3
    {0x21, 0x01}, // ctrl_4
    {0x22, 0x01}, // ctrl_5
    {0x23, 0x01}, // ctrl_6
    {0x24, 0x01}, // ctrl_7
    {0x25, 0x01}, // ctrl_8
    {0x26, 0x01}, // ctrl_9
    {0x27, 0x01}, // ctrl_0
    {0x14, 0x01}, // ctrl_q
    {0x1A, 0x01}, // ctrl_w
    {0x08, 0x01}, // ctrl_e
    {0x15, 0x01}, // ctrl_r
    {0x17, 0x01}, // ctrl_t

    // Normal keys C2 to C7 (notes 36-95)
    {0x1E, 0x00}, // "1"
    {0x1E, 0x02}, // "!"
    {0x1F, 0x00}, // "2"
    {0x1F, 0x02}, // "@"
    {0x20, 0x00}, // "3"
    {0x21, 0x00}, // "4"
    {0x21, 0x02}, // "$"
    {0x22, 0x00}, // "5"
    {0x22, 0x02}, // "%"
    {0x23, 0x00}, // "6"
    {0x23, 0x02}, // "^"
    {0x24, 0x00}, // "7"
    {0x25, 0x00}, // "8"
    {0x25, 0x02}, // "*"
    {0x26, 0x00}, // "9"
    {0x26, 0x02}, // "("
    {0x27, 0x00}, // "0"
    {0x14, 0x00}, // "q"
    {0x14, 0x02}, // "Q"
    {0x1A, 0x00}, // "w"
    {0x1A, 0x02}, // "W"
    {0x08, 0x00}, // "e"
    {0x08, 0x02}, // "E"
    {0x15, 0x00}, // "r"
    {0x17, 0x00}, // "t"
    {0x17, 0x02}, // "T"
    {0x1C, 0x00}, // "y"
    {0x1C, 0x02}, // "Y"
    {0x18, 0x00}, // "u"
    {0x0C, 0x00}, // "i"
    {0x0C, 0x02}, // "I"
    {0x12, 0x00}, // "o"
    {0x12, 0x02}, // "O"
    {0x13, 0x00}, // "p"
    {0x13, 0x02}, // "P"
    {0x04, 0x00}, // "a"
    {0x16, 0x00}, // "s"
    {0x16, 0x02}, // "S"
    {0x07, 0x00}, // "d"
    {0x07, 0x02}, // "D"
    {0x09, 0x00}, // "f"
    {0x0A, 0x00}, // "g"
    {0x0A, 0x02}, // "G"
    {0x0B, 0x00}, // "h"
    {0x0B, 0x02}, // "H"
    {0x0D, 0x00}, // "j"
    {0x0D, 0x02}, // "J"
    {0x0E, 0x00}, // "k"
    {0x0F, 0x00}, // "l"
    {0x0F, 0x02}, // "L"
    {0x1D, 0x00}, // "z"
    {0x1D, 0x02}, // "Z"
    {0x1B, 0x00}, // "x"
    {0x06, 0x00}, // "c"
    {0x06, 0x02}, // "C"
    {0x19, 0x00}, // "v"
    {0x19, 0x02}, // "V"
    {0x05, 0x00}, // "b"
    {0x05, 0x02}, // "B"
    {0x11, 0x00}, // "n"
    {0x10, 0x00}, // "m"

    // C#7 to C8 (notes 97-108) — ctrl_ prefix = meta 0x01
    {0x1C, 0x01}, // ctrl_y
    {0x18, 0x01}, // ctrl_u
    {0x0C, 0x01}, // ctrl_i
    {0x12, 0x01}, // ctrl_o
    {0x13, 0x01}, // ctrl_p
    {0x04, 0x01}, // ctrl_a
    {0x16, 0x01}, // ctrl_s
    {0x07, 0x01}, // ctrl_d
    {0x09, 0x01}, // ctrl_f
    {0x0A, 0x01}, // ctrl_g
    {0x0B, 0x01}, // ctrl_h
    {0x0D, 0x01}, // ctrl_j
};

// ============================================================================
// UHID kernel-event drain thread.
// ----------------------------------------------------------------------------
// /dev/uhid is bidirectional: write() sends HID reports TO the kernel; read()
// receives UHID_START / UHID_STOP / UHID_OPEN / UHID_CLOSE / UHID_OUTPUT
// events FROM the kernel (e.g. when an app starts using the keyboard, or
// requests a LED state change).
//
// If we never read these, they accumulate in the kernel side buffer. On most
// kernels that's bounded and harmless, but on some Android variants a full
// queue starts to throttle writes — exactly the kind of subtle latency
// regression that "feels parah" without showing up in any single benchmark.
//
// A dedicated drain thread blocks on read() and tosses every event. Tiny
// (~1 syscall per app focus change) and self-terminates when uhid_fd closes.
// ============================================================================
static std::thread g_drainThread;
static std::atomic<bool> g_drainRun{false};

static void uhidDrainLoop(int fd) {
    struct pollfd pfd;
    while (g_drainRun.load(std::memory_order_relaxed)) {
        pfd.fd = fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        int pr = poll(&pfd, 1, 500);
        if (pr <= 0) continue; // timeout or interrupted
        if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) break;
        if (pfd.revents & POLLIN) {
            struct uhid_event ev;
            ssize_t n = read(fd, &ev, sizeof(ev));
            if (n < 0) {
                if (errno == EAGAIN || errno == EINTR) continue;
                break;
            }
            // We don't care WHAT the kernel sent — discarding is fine. The
            // point is just to keep the queue empty so no back-pressure
            // ever bleeds into write() latency.
        }
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_panpanpan_app_GamePadNative_nativeCreateUHid(JNIEnv *env, jclass clazz) {
    __android_log_print(ANDROID_LOG_WARN, TAG, "HELLO WORLD THIS IS FROM JNI");

    // Daemon process priority: this JNI runs inside the helper daemon spawned
    // by Shizuku/root. Boost the WHOLE process to URGENT_AUDIO-equivalent
    // nice (-19) once at startup so every binder thread it spins up later
    // inherits the low-jitter scheduling. Per-thread boost in nativeQwertyKey
    // is still kept as a belt-and-suspenders for new threads added by the
    // binder pool. setpriority(PRIO_PROCESS,0,...) without CAP_SYS_NICE is a
    // no-op (returns -1) — safe to call unconditionally.
    setpriority(PRIO_PROCESS, 0, -19);

    // Stop any drain thread from a previous Create (e.g. uHID re-create after
    // a daemon respawn). It was polling the now-stale fd; spawn a fresh one
    // for the new fd below. Idempotent if no thread was running.
    if (g_drainRun.exchange(false)) {
        if (g_drainThread.joinable()) {
            try { g_drainThread.join(); } catch (...) {}
        }
    }

    if ((uhid_fd = open("/dev/uhid", O_RDWR | O_NDELAY)) < 0) {
        return false;//error process.
    }
    struct uhid_event ev;
    memset(&ev, 0, sizeof(uhid_event));
    ev.type = UHID_CREATE;
    strcpy((char *) ev.u.create.name, "uHidKeyboard");
    ev.u.create.rd_data = description;
    ev.u.create.rd_size = sizeof(description);
    ev.u.create.bus = 0x03;
    ev.u.create.vendor = 0x1;
    ev.u.create.product = 0x1;
    ev.u.create.version = 0x1;
    ev.u.create.country = 0;
    if (write(uhid_fd, &ev, sizeof(ev)) != sizeof(uhid_event)) {
        return false;
    }

    memset(&uhidEvent, 0, sizeof(uhid_event));
    uhidEvent.type = UHID_INPUT;
    uhidEvent.u.input.size = REPORT_SIZE;
    uhidEvent.u.input.data[0] = 0x00;

    // Reset last-report cache so dedup doesn't think the brand-new uHID
    // device already received {0,0,0,0,0,0,0,0}. (Fresh device starts in
    // unknown state — first emit must hit the wire.)
    memset(g_lastReport, 0xFF, sizeof(g_lastReport));

    // Spawn a fresh drain thread for THIS fd.
    g_drainRun.store(true);
    int fd_copy = uhid_fd;
    g_drainThread = std::thread([fd_copy]() { uhidDrainLoop(fd_copy); });

    return true;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_panpanpan_app_GamePadNative_nativeCloseUHid(JNIEnv *env, jclass clazz) {

    // Stop drain thread first so it doesn't race against fd close.
    g_drainRun.store(false);
    if (g_drainThread.joinable()) {
        try { g_drainThread.join(); } catch (...) {}
    }

    // Clear held-keys state biar gak ada stale data pas uHID di-recreate.
    {
        std::lock_guard<std::mutex> lock(g_heldMutex);
        g_heldKeys.clear();
        g_lastVelHex = -1; // reset velocity level tracking
        memset(g_lastReport, 0, sizeof(g_lastReport));
    }

    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_DESTROY;
    ssize_t r = (uhid_fd >= 0) ? write(uhid_fd, &ev, sizeof(uhid_event)) : -1;
    if (uhid_fd >= 0) { close(uhid_fd); uhid_fd = -1; }
    return r > 0;
}


// qwerty mode function [works on most roblox piano games]

extern "C"
JNIEXPORT void JNICALL
Java_com_panpanpan_app_GamePadNative_nativeQwertyKey(JNIEnv *env,
                                                     jclass thiz,
                                                     jint noteNumber,
                                                     jboolean isDown,
                                                     jint velocity) {
    if (!(noteNumber >= 21 && noteNumber <= 108)) {
        return; // silent drop — no log on hot path
    }
    // Range is 21..108 inclusive = the full 88-key piano (A0..C8).
    // g_keyLookup has NUM_KEYS(88) entries: index 0 = note 21 (A0),
    // index 87 = note 108 (C8 -> ctrl_j). Capping at 107 used to drop C8.

    // Latency/jitter: daemon nanganin note event lewat binder oneway dari MIDI
    // dispatcher. Binder pakai thread POOL, jadi boost dilakukan PER-THREAD
    // (thread_local) sekali doang tiap binder thread yg pernah ngelayanin not.
    // Naikin nice ke -19 (setara URGENT_AUDIO) biar thread injeksi HID gak
    // kena preempt → ngurangin jitter di sisi daemon.
    //
    // setpriority(PRIO_PROCESS, 0, ...) di Linux = nice thread pemanggil
    // (nice itu per-task). CUMA nice value, bukan RT scheduling → gak bisa
    // nge-starve sistem. Kalau proses gak punya CAP_SYS_NICE, panggilan gagal
    // graceful (return -1) tanpa efek samping — thread tetap di prioritas normal.
    static thread_local bool t_prioBoosted = false;
    if (!t_prioBoosted) {
        t_prioBoosted = true;
        setpriority(PRIO_PROCESS, 0, -19);
    }

    // Zero-allocation lookup: direct array index, no string, no hash
    const KeyEntry& key = g_keyLookup[noteNumber - 21];

    std::lock_guard<std::mutex> lock(g_heldMutex);

    if (isDown) {
        // Dedupe: skip if already held
        for (const auto& h : g_heldKeys) {
            if (h.note == noteNumber) return;
        }

        // VELOCITY: velocity>0 artinya Visual Piano velocity mode aktif untuk
        // event ini (gating ON/OFF dilakukan di MidiBridgeService — OFF kirim 0).
        // Tap Alt+velKey SEBELUM not ditekan, dan cuma kalau level berubah.
        // Dilakukan sebelum push not baru supaya tapVelocityKey cuma bawa not
        // yg lagi bener-bener di-hold (bukan not baru ini).
        if (velocity > 0) {
            int velHex = g_velKeyHex[velocityToIndex(velocity)];
            if (velHex != g_lastVelHex) {
                g_lastVelHex = velHex;
                tapVelocityKey(velHex);
            }
        }

        // Meta collision fix: if new note has a DIFFERENT modifier than
        // what's currently held, drop the held set first. HID report only
        // has 1 meta byte shared across every held key (NKRO bitmap removed
        // the 6-key cap, but not this — it's one modifier byte regardless of
        // how many keys are held) — mixing notes with different modifiers
        // causes wrong key interpretation.
        // e.g. holding "q" (meta=0x00) + pressing "ctrl_1" (meta=0x01)
        // would make Roblox see Ctrl+q = wrong note.
        // Roblox sustain is absolute (hold=sound, release=stop) so
        // dropping doesn't cause audio gaps — notes just get re-triggered.
        //
        // HEX-COLLISION CASE (the subtle one):
        //   Many natural+sharp pairs share the SAME HID keycode and only
        //   differ by modifier — e.g. note 69 (A4 = "p") and note 70
        //   (Bb4 = "P") both use 0x13. If user plays A→Bb with A still
        //   held (legato), the next emitHeldReport would write a report
        //   that only flips the modifier; key 0x13 stays down across
        //   reports so the kernel emits NO KEY_P transition and Roblox's
        //   InputBegan handler never fires for the new note → Bb is
        //   silent even though shift went down. We MUST insert an
        //   intermediate empty report so the shared key cycles up→down,
        //   forcing Roblox to see a fresh InputBegan with the new
        //   modifier state.
        //
        //   When there's NO hex collision (e.g. holding "q" then
        //   pressing "!"), the single emitHeldReport at the end of the
        //   press path already produces a clean release-old + press-new
        //   transition in one report — no intermediate write needed.
        if (!g_heldKeys.empty()) {
            int currentMeta = 0;
            bool hexCollision = false;
            for (const auto& h : g_heldKeys) {
                currentMeta |= h.meta;
                if (h.hex == key.hex) hexCollision = true;
            }
            if (currentMeta != key.meta) {
                g_heldKeys.clear();
                if (hexCollision) {
                    writeKeyboard(); // empty report — force the shared key to cycle
                }
            }
        }

        // No eviction needed: NKRO bitmap gives every held note its own bit,
        // bounded only by g_heldKeys' natural cap of 88 (one MIDI note range
        // check already filters noteNumber to 21..108 above).
        g_heldKeys.push_back({static_cast<int>(noteNumber), key.meta, key.hex});
        emitHeldReport();
    } else {
        size_t before = g_heldKeys.size();
        g_heldKeys.erase(
            std::remove_if(g_heldKeys.begin(), g_heldKeys.end(),
                [noteNumber](const HeldKey& h) { return h.note == noteNumber; }),
            g_heldKeys.end()
        );
        if (g_heldKeys.size() != before) {
            emitHeldReport();
        }
    }
}

