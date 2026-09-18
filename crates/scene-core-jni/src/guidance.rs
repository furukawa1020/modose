//! Guidance wire v1: [version, object_id, kind, payload...], table meters.
//! kind 0: none; 1: from x/z, to x/z, distance; 2/3: target x/z;
//! kind 4: recovery reason (0 unobserved, 1 tracking lost, 2 missing, 3 ambiguous).
use jni::objects::JObject;
use jni::sys::{jdoubleArray, jlong};
use jni::JNIEnv;
use scene_core::guidance::{Guidance, GuidanceAction, RecoveryReason};
use scene_core::native_runtime::SessionHandle;

use super::{boundary, runtime, Failure};

fn encode(guidance: Option<Guidance>) -> Vec<f64> {
    let Some(guidance) = guidance else { return vec![1.0, 0.0, 0.0]; };
    let id = f64::from(guidance.object_id);
    match guidance.action {
        GuidanceAction::Move { from, to, distance_m } =>
            vec![1.0, id, 1.0, from.x, from.z, to.x, to.z, distance_m],
        GuidanceAction::Ring { target } => vec![1.0, id, 2.0, target.x, target.z],
        GuidanceAction::CheckOrientation { target } =>
            vec![1.0, id, 3.0, target.x, target.z],
        GuidanceAction::Recover { reason } => {
            let reason = match reason {
                RecoveryReason::Unobserved => 0.0,
                RecoveryReason::TrackingLost => 1.0,
                RecoveryReason::Missing => 2.0,
                RecoveryReason::Ambiguous => 3.0,
            };
            vec![1.0, id, 4.0, reason]
        }
    }
}

// SAFETY: Matches NativeSceneBindings.nativeGuidance(Long, Long): DoubleArray.
// No Java reference is retained. Calls share the existing registry lock.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_modose_app_core_NativeSceneBindings_nativeGuidance(
    mut env: JNIEnv<'_>, _owner: JObject<'_>, raw_handle: jlong, now_ms: jlong,
) -> jdoubleArray {
    boundary(&mut env, std::ptr::null_mut(), |env| {
        let mut registry = runtime().lock().map_err(|_| "Native registry unavailable")?;
        let handle = SessionHandle::from_raw(raw_handle).map_err(|_| "Invalid session handle")?;
        let result = (|| -> Result<jdoubleArray, Failure> {
            let now = u64::try_from(now_ms).map_err(|_| "Invalid guidance time")?;
            let values = encode(registry.core.guidance(handle, now)
                .map_err(|_| "Native guidance rejected")?);
            let array = env.new_double_array(values.len() as i32)
                .map_err(|_| "Guidance allocation failed")?;
            env.set_double_array_region(&array, 0, &values)
                .map_err(|_| "Guidance copy failed")?;
            Ok(array.into_raw())
        })();
        if result.is_err() {
            let _ = registry.close(handle);
        }
        result
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use scene_core::local_restoration::TablePosition;

    #[test]
    fn wire_preserves_meters_and_object_identity() {
        let action = GuidanceAction::Move {
            from: TablePosition { x: 0.3, z: -0.4 },
            to: TablePosition { x: 0.0, z: 0.0 },
            distance_m: 0.5,
        };
        assert_eq!(encode(Some(Guidance { object_id: 7, action })),
            vec![1.0, 7.0, 1.0, 0.3, -0.4, 0.0, 0.0, 0.5]);
    }

    #[test]
    fn no_guidance_and_recovery_never_contain_positions() {
        assert_eq!(encode(None), vec![1.0, 0.0, 0.0]);
        for (reason, code) in [
            (RecoveryReason::Unobserved, 0.0), (RecoveryReason::TrackingLost, 1.0),
            (RecoveryReason::Missing, 2.0), (RecoveryReason::Ambiguous, 3.0),
        ] {
            assert_eq!(encode(Some(Guidance {
                object_id: 1, action: GuidanceAction::Recover { reason },
            })), vec![1.0, 1.0, 4.0, code]);
        }
    }

    #[test]
    fn ring_and_orientation_have_distinct_tags() {
        let target = TablePosition { x: 0.1, z: 0.2 };
        for (action, code) in [
            (GuidanceAction::Ring { target }, 2.0),
            (GuidanceAction::CheckOrientation { target }, 3.0),
        ] {
            assert_eq!(encode(Some(Guidance { object_id: 1, action })),
                vec![1.0, 1.0, code, 0.1, 0.2]);
        }
    }
}
