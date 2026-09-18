use std::collections::BTreeMap;

use jni::objects::{JIntArray, JObject};
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use scene_core::native_runtime::{NativeRuntime, SessionHandle};
use scene_core::restoration_session::{RestoreState, VerificationResult, VerificationTicket, Verdict};

use super::{boundary, runtime, state_code, Failure, Registry};

#[derive(Default)]
pub(super) struct VerificationBridge {
    next_token: u64,
    pending: BTreeMap<SessionHandle, (i64, VerificationTicket)>,
}

impl VerificationBridge {
    pub(super) fn discard(&mut self, handle: SessionHandle) {
        self.pending.remove(&handle);
    }

    fn begin(&mut self, core: &mut NativeRuntime, handle: SessionHandle, now: u64)
        -> Result<i64, Failure>
    {
        if self.next_token >= i64::MAX as u64 { return Err("Verification tokens exhausted"); }
        let ticket = core.begin_verification(handle, now).map_err(|_| "Verification cannot begin")?;
        self.next_token += 1;
        let token = self.next_token as i64;
        self.pending.insert(handle, (token, ticket));
        Ok(token)
    }

    fn complete(
        &mut self, core: &mut NativeRuntime, handle: SessionHandle, token: i64,
        now: u64, result: VerificationResult,
    ) -> Result<RestoreState, Failure> {
        let (expected, ticket) = self.pending.get(&handle).copied()
            .ok_or("Verification ticket missing")?;
        if token <= 0 || token != expected { return Err("Verification ticket mismatch"); }
        self.pending.remove(&handle);
        core.complete_verification(handle, ticket, now, result)
            .map_err(|_| "Verification result rejected")
    }
}

fn verdict(value: jint) -> Result<Verdict, Failure> {
    match value {
        0 => Ok(Verdict::Verified),
        1 => Ok(Verdict::NeedsCorrection),
        2 => Ok(Verdict::Uncertain),
        _ => Err("Invalid verification verdict"),
    }
}

// Overall 3 represents a transport failure and requires two empty arrays.
// Other overall values and object values: 0 verified, 1 correction, 2 uncertain.
fn read_result(
    env: &JNIEnv<'_>, overall: jint, ids: &JIntArray<'_>, verdicts: &JIntArray<'_>,
) -> Result<VerificationResult, Failure> {
    let count = env.get_array_length(ids).map_err(|_| "Invalid verification IDs")?;
    let verdict_count = env.get_array_length(verdicts).map_err(|_| "Invalid verdict array")?;
    if count != verdict_count { return Err("Verification array lengths differ"); }
    if overall == 3 {
        return if count == 0 { Ok(VerificationResult::Unavailable) }
            else { Err("Unavailable result contains objects") };
    }
    let overall = verdict(overall)?;
    if !(1..=5).contains(&count) { return Err("Invalid verification object count"); }
    let mut ids_copy = vec![0; count as usize];
    let mut verdicts_copy = vec![0; count as usize];
    env.get_int_array_region(ids, 0, &mut ids_copy).map_err(|_| "Verification ID copy failed")?;
    env.get_int_array_region(verdicts, 0, &mut verdicts_copy).map_err(|_| "Verdict copy failed")?;
    let mut objects = Vec::with_capacity(count as usize);
    for (id, value) in ids_copy.into_iter().zip(verdicts_copy) {
        if id <= 0 || objects.iter().any(|(other, _)| *other == id as u32) {
            return Err("Invalid or duplicate verification ID");
        }
        objects.push((id as u32, verdict(value)?));
    }
    Ok(VerificationResult::Analyzed { overall, objects })
}

// SAFETY: These instance JNI symbols match NativeSceneBindings declarations.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_modose_app_core_NativeSceneBindings_nativeBeginVerification(
    mut env: JNIEnv<'_>, _owner: JObject<'_>, raw_handle: jlong, observed_at_ms: jlong,
) -> jlong {
    boundary(&mut env, 0, |_| {
        let mut registry = runtime().lock().map_err(|_| "Native registry unavailable")?;
        let handle = SessionHandle::from_raw(raw_handle).map_err(|_| "Invalid session handle")?;
        let result = (|| {
            let now = u64::try_from(observed_at_ms).map_err(|_| "Invalid observation time")?;
            let Registry { core, verification } = &mut *registry;
            verification.begin(core, handle, now)
        })();
        if result.is_err() { let _ = registry.close(handle); }
        result
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_modose_app_core_NativeSceneBindings_nativeCompleteVerification(
    mut env: JNIEnv<'_>, _owner: JObject<'_>, raw_handle: jlong, token: jlong,
    observed_at_ms: jlong, overall: jint, ids: JIntArray<'_>, verdicts: JIntArray<'_>,
) -> jint {
    boundary(&mut env, -1, |env| {
        let mut registry = runtime().lock().map_err(|_| "Native registry unavailable")?;
        let handle = SessionHandle::from_raw(raw_handle).map_err(|_| "Invalid session handle")?;
        let result = (|| {
            let now = u64::try_from(observed_at_ms).map_err(|_| "Invalid observation time")?;
            let result = read_result(env, overall, &ids, &verdicts)?;
            let Registry { core, verification } = &mut *registry;
            verification.complete(core, handle, token, now, result).map(state_code)
        })();
        if result.is_err() { let _ = registry.close(handle); }
        result
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use scene_core::local_restoration::TablePosition;
    use scene_core::matched_restoration::{CurrentDetection, RestorationEvidence};
    use scene_core::object_assignment::PairEvidence;
    use scene_core::pair_confidence::PairScore;
    use scene_core::table_projection::{TablePlane, WorldRay, WorldVector};

    fn vector(x: f64, y: f64, z: f64) -> WorldVector { WorldVector { x, y, z } }
    fn aligned(core: &mut NativeRuntime) -> SessionHandle {
        let boundary = [(-1.0,-1.0), (1.0,-1.0), (1.0,1.0), (-1.0,1.0)]
            .map(|(x,z)| TablePosition { x, z });
        let plane = TablePlane::new(vector(0.0,0.0,0.0), vector(1.0,0.0,0.0),
            vector(0.0,0.0,1.0), &boundary).unwrap();
        let handle = core.create(plane, &[(1, TablePosition { x: 0.0, z: 0.0 })]).unwrap();
        let detection = CurrentDetection {
            current_id: 10, occludes_other: false,
            ray: WorldRay { origin: vector(0.0,1.0,0.0), direction: vector(0.0,-1.0,0.0) },
        };
        let evidence = RestorationEvidence {
            pair: PairEvidence { saved_id: 1, current_id: 10, score: PairScore::new(1.0,1.0,1.0).unwrap() },
            orientation_aligned: true,
        };
        for now in (0..=800).step_by(100) {
            core.update_frame(handle, now, true, &[detection], &[evidence]).unwrap();
        }
        handle
    }
    fn verified() -> VerificationResult {
        VerificationResult::Analyzed { overall: Verdict::Verified, objects: vec![(1, Verdict::Verified)] }
    }

    #[test]
    fn valid_ticket_verifies_once_and_cannot_be_replayed() {
        let mut core = NativeRuntime::default();
        let handle = aligned(&mut core);
        let mut bridge = VerificationBridge::default();
        let token = bridge.begin(&mut core, handle, 800).unwrap();
        assert!(token > 0);
        assert_eq!(bridge.complete(&mut core, handle, token, 800, verified()), Ok(RestoreState::Verified));
        assert!(bridge.complete(&mut core, handle, token, 800, verified()).is_err());
    }

    #[test]
    fn another_owners_ticket_does_not_consume_the_correct_ticket() {
        let mut core = NativeRuntime::default();
        let first = aligned(&mut core);
        let second = aligned(&mut core);
        let mut bridge = VerificationBridge::default();
        let first_token = bridge.begin(&mut core, first, 800).unwrap();
        let second_token = bridge.begin(&mut core, second, 800).unwrap();
        assert_ne!(first_token, second_token);
        assert!(bridge.complete(&mut core, second, first_token, 800, verified()).is_err());
        assert_eq!(bridge.complete(&mut core, second, second_token, 800, verified()), Ok(RestoreState::Verified));
        assert_eq!(bridge.complete(&mut core, first, first_token, 800, verified()), Ok(RestoreState::Verified));
    }

    #[test]
    fn three_unavailable_results_never_become_success() {
        let mut core = NativeRuntime::default();
        let handle = aligned(&mut core);
        let mut bridge = VerificationBridge::default();
        let mut previous = 0;
        for attempt in 1..=3 {
            let token = bridge.begin(&mut core, handle, 800).unwrap();
            assert!(token > previous);
            previous = token;
            let state = bridge.complete(&mut core, handle, token, 800, VerificationResult::Unavailable).unwrap();
            assert_eq!(state, if attempt == 3 { RestoreState::ManualConfirmation }
                else { RestoreState::AwaitingVerification });
        }
    }

    #[test]
    fn discarded_and_expired_confirmations_cannot_verify() {
        let mut core = NativeRuntime::default();
        let handle = aligned(&mut core);
        let mut bridge = VerificationBridge::default();
        let token = bridge.begin(&mut core, handle, 800).unwrap();
        bridge.discard(handle);
        assert!(bridge.complete(&mut core, handle, token, 800, verified()).is_err());
        let second = aligned(&mut core);
        let token = bridge.begin(&mut core, second, 800).unwrap();
        assert!(bridge.complete(&mut core, second, token, 1001, verified()).is_err());
    }
}
