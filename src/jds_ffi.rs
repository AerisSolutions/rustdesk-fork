// JDS: provisioning from the companion app, applied natively
// Each call is just a RustDesk config write it already supports
use jni::{
    objects::{JClass, JString},
    sys::jstring,
    JNIEnv,
};

#[no_mangle]
pub unsafe extern "system" fn Java_ffi_JdsFFI_setOption(
    mut env: JNIEnv,
    _: JClass,
    key: JString,
    value: JString,
) {
    if let (Ok(k), Ok(v)) = (env.get_string(&key), env.get_string(&value)) {
        crate::ui_interface::set_option(k.into(), v.into());
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_ffi_JdsFFI_setLocalOption(
    mut env: JNIEnv,
    _: JClass,
    key: JString,
    value: JString,
) {
    if let (Ok(k), Ok(v)) = (env.get_string(&key), env.get_string(&value)) {
        crate::ui_interface::set_local_option(k.into(), v.into());
    }
}

// sets the permanent password and returns this device's RustDesk ID, or "" on failure
#[no_mangle]
pub unsafe extern "system" fn Java_ffi_JdsFFI_setPasswordAndGetId(
    mut env: JNIEnv,
    _: JClass,
    password: JString,
) -> jstring {
    let id = if let Ok(p) = env.get_string(&password) {
        let p: String = p.into();
        if !p.is_empty() && crate::ui_interface::set_permanent_password_with_result(p) {
            crate::ui_interface::get_id()
        } else {
            "".into()
        }
    } else {
        "".into()
    };
    env.new_string(id).unwrap_or_default().into_raw()
}
