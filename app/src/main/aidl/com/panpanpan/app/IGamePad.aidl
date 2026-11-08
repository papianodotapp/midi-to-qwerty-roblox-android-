package com.panpanpan.app;

interface IGamePad {

    boolean create();

    boolean close();

    void closeAndExit();

    // oneway: fire-and-forget async transaction. Caller (note hot-path) doesn't
    // block waiting for the daemon round-trip -> lower per-note latency. Oneway
    // calls to the same binder are delivered in order, so note on/off ordering
    // is preserved. Dead-binder still throws at transact time (caught upstream).
    oneway void qwertyKey(int key, boolean isDown, int velocity);

    // Lepas SEMUA key yg lagi di-hold di virtual keyboard (all-notes-off).
    // Dipanggil saat MIDI device dicabut / port ditutup / service mati, supaya
    // gak ada key (apalagi Ctrl/Shift/Alt) yg nyangkut ditekan selamanya.
    // Ditaruh PALING BAWAH supaya transaction code method lain gak geser
    // (daemon versi lama yg masih jalan tetep kompatibel).
    oneway void releaseAll();
}