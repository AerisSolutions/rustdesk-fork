package ffi

// JDS: our own native calls (src/jds_ffi.rs), kept out of upstream's ffi.kt
// acts as a bridge between JdsProvisioning.kt (Kotlin) and jds_ffi.rs (Rust)
object JdsFFI {
    init {
        System.loadLibrary("rustdesk")
    }

    external fun setOption(key: String, value: String)
    external fun setLocalOption(key: String, value: String)

    // sets the permanent password and returns this device's RustDesk ID, or "" on failure
    external fun setPasswordAndGetId(password: String): String
}
