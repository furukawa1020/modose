//! Narrow JNI boundary. Java references never outlive an exported call.
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Mutex, OnceLock};

use jni::objects::{JByteArray, JDoubleArray, JIntArray, JObject};
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use scene_core::frame_ingress::{apply_frame_packet, MAX_FRAME_PACKET_BYTES};
use scene_core::local_restoration::TablePosition;
use scene_core::native_runtime::{NativeError, NativeRuntime, SessionHandle};
use scene_core::restoration_session::RestoreState;
use scene_core::table_projection::{TablePlane, WorldVector};

mod verification;

#[derive(Default)]
struct Registry {
    core: NativeRuntime,
    verification: verification::VerificationBridge,
}

impl Registry {
    fn close(&mut self, handle: SessionHandle) -> Result<(), NativeError> {
        self.verification.discard(handle);
        self.core.close(handle)
    }
}

static RUNTIME: OnceLock<Mutex<Registry>> = OnceLock::new();
type Failure = &'static str;

fn runtime() -> &'static Mutex<Registry> {
    RUNTIME.get_or_init(|| Mutex::new(Registry::default()))
}

/// Preserve an existing Java exception; never return an error sentinel silently.
fn raise(env: &mut JNIEnv<'_>, message: Failure) {
    match env.exception_check() {
        Ok(true) => {}
        Ok(false) => {
            if env.throw_new("java/lang/IllegalStateException", message).is_err() {
                env.fatal_error("Unable to report native session failure");
            }
        }
        Err(_) => env.fatal_error("Unable to inspect JNI exception state"),
    }
}

fn boundary<T: Copy>(
    env: &mut JNIEnv<'_>,
    failure: T,
    operation: impl FnOnce(&mut JNIEnv<'_>) -> Result<T, Failure>,
) -> T {
    match catch_unwind(AssertUnwindSafe(|| operation(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => { raise(env, message); failure }
        Err(_) => {
            // A panic while holding the registry lock poisons it. Future calls
            // reject the registry instead of reusing potentially damaged state.
            raise(env, "Native session panic");
            failure
        }
    }
}

fn doubles(env: &JNIEnv<'_>, array: &JDoubleArray<'_>, min: usize, max: usize)
    -> Result<Vec<f64>, Failure>
{
    let count = env.get_array_length(array).map_err(|_| "Invalid double array")? as usize;
    if !(min..=max).contains(&count) { return Err("Invalid double array length"); }
    let mut values = vec![0.0; count];
    env.get_double_array_region(array, 0, &mut values).map_err(|_| "Double array copy failed")?;
    if values.iter().any(|v| !v.is_finite()) { return Err("Non-finite geometry"); }
    Ok(values)
}

/// Geometry: origin xyz, x-axis xyz, z-axis xyz, then 3..64 boundary (x,z) pairs.
fn plane(geometry: &[f64]) -> Result<TablePlane, Failure> {
    if !(15..=137).contains(&geometry.len()) || (geometry.len() - 9) % 2 != 0 {
        return Err("Invalid plane geometry length");
    }
    let vector = |i| WorldVector { x: geometry[i], y: geometry[i + 1], z: geometry[i + 2] };
    let polygon: Vec<_> = geometry[9..].chunks_exact(2)
        .map(|p| TablePosition { x: p[0], z: p[1] }).collect();
    TablePlane::new(vector(0), vector(3), vector(6), &polygon)
        .map_err(|_| "Invalid plane geometry")
}

fn state_code(state: RestoreState) -> jint {
    match state {
        RestoreState::Guiding => 0,
        RestoreState::AwaitingVerification => 1,
        RestoreState::Verifying => 2,
        RestoreState::Verified => 3,
        RestoreState::ManualConfirmation => 4,
    }
}

// SAFETY: Export names and signatures match the instance native methods on
// com.modose.app.core.NativeSceneBindings. Only the JVM calls these exports.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_modose_app_core_NativeSceneBindings_nativeCreate(
    mut env: JNIEnv<'_>, _owner: JObject<'_>, geometry: JDoubleArray<'_>,
    ids: JIntArray<'_>, positions: JDoubleArray<'_>,
) -> jlong {
    boundary(&mut env, 0, |env| {
        let mut registry = runtime().lock().map_err(|_| "Native registry unavailable")?;
        let geometry = doubles(env, &geometry, 15, 137)?;
        let count = env.get_array_length(&ids).map_err(|_| "Invalid target IDs")? as usize;
        if !(1..=5).contains(&count) { return Err("Invalid target count"); }
        let mut ids_copy = vec![0; count];
        env.get_int_array_region(&ids, 0, &mut ids_copy).map_err(|_| "Target ID copy failed")?;
        if ids_copy.iter().any(|id| *id <= 0) { return Err("Invalid target ID"); }
        let positions = doubles(env, &positions, count * 2, count * 2)?;
        let targets: Vec<_> = ids_copy.iter().zip(positions.chunks_exact(2))
            .map(|(id, p)| (*id as u32, TablePosition { x: p[0], z: p[1] })).collect();
        registry.core.create(plane(&geometry)?, &targets).map(|handle| handle.as_raw())
            .map_err(|_| "Native session creation rejected")
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_modose_app_core_NativeSceneBindings_nativeApply(
    mut env: JNIEnv<'_>, _owner: JObject<'_>, raw_handle: jlong,
    observed_at_ms: jlong, packet: JByteArray<'_>,
) -> jint {
    boundary(&mut env, -1, |env| {
        let mut registry = runtime().lock().map_err(|_| "Native registry unavailable")?;
        let handle = SessionHandle::from_raw(raw_handle).map_err(|_| "Invalid session handle")?;
        let result = (|| {
            let now = u64::try_from(observed_at_ms).map_err(|_| "Invalid observation time")?;
            let count = env.get_array_length(&packet).map_err(|_| "Invalid frame array")? as usize;
            if count > MAX_FRAME_PACKET_BYTES { return Err("Frame exceeds size limit"); }
            let bytes = env.convert_byte_array(&packet).map_err(|_| "Frame copy failed")?;
            apply_frame_packet(&mut registry.core, handle, now, &bytes).map(state_code)
                .map_err(|_| "Native frame update rejected")
        })();
        if !matches!(result, Ok(2)) {
            registry.verification.discard(handle);
        }
        if result.is_err() {
            // Includes malformed arrays and invalid clocks before core ingress.
            // This handle is terminal; another session is never touched.
            let _ = registry.close(handle);
        }
        result
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_modose_app_core_NativeSceneBindings_nativeClose(
    mut env: JNIEnv<'_>, _owner: JObject<'_>, raw_handle: jlong,
) {
    boundary(&mut env, (), |_| {
        let mut registry = runtime().lock().map_err(|_| "Native registry unavailable")?;
        let handle = SessionHandle::from_raw(raw_handle).map_err(|_| "Invalid session handle")?;
        registry.close(handle).map_err(|_| "Unknown or closed session")
    });
}
