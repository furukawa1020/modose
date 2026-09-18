use scene_core::local_restoration::TablePosition;
use scene_core::matched_restoration::{CurrentDetection, RestorationEvidence};
use scene_core::native_runtime::*;
use scene_core::object_assignment::PairEvidence;
use scene_core::pair_confidence::PairScore;
use scene_core::restoration_session::{RestoreState, VerificationResult, Verdict};
use scene_core::table_projection::{TablePlane, WorldRay, WorldVector};

fn point(x: f64, z: f64) -> TablePosition { TablePosition { x, z } }
fn vector(x: f64, y: f64, z: f64) -> WorldVector { WorldVector { x, y, z } }
fn plane() -> TablePlane {
    TablePlane::new(vector(0.0, 0.0, 0.0), vector(1.0, 0.0, 0.0),
        vector(0.0, 0.0, 1.0), &[
            point(-1.0, -1.0), point(1.0, -1.0), point(1.0, 1.0), point(-1.0, 1.0),
        ]).unwrap()
}
fn create(runtime: &mut NativeRuntime) -> SessionHandle {
    runtime.create(plane(), &[(1, point(0.0, 0.0))]).unwrap()
}
fn align(runtime: &mut NativeRuntime, handle: SessionHandle) {
    let detection = CurrentDetection {
        current_id: 10, occludes_other: false,
        ray: WorldRay { origin: vector(0.0, 1.0, 0.0), direction: vector(0.0, -1.0, 0.0) },
    };
    let evidence = RestorationEvidence {
        pair: PairEvidence { saved_id: 1, current_id: 10, score: PairScore::new(1.0, 1.0, 1.0).unwrap() },
        orientation_aligned: true,
    };
    for time in (0..=800).step_by(100) {
        runtime.update_frame(handle, time, true, &[detection], &[evidence]).unwrap();
    }
}

#[test]
fn handles_round_trip_and_nonpositive_values_fail() {
    for value in [i64::MIN, -1, 0] {
        assert_eq!(SessionHandle::from_raw(value), Err(NativeError::UnknownHandle));
    }
    let mut runtime = NativeRuntime::default();
    let handle = create(&mut runtime);
    assert!(handle.as_raw() > 0);
    assert_eq!(SessionHandle::from_raw(handle.as_raw()), Ok(handle));
}

#[test]
fn capacity_is_bounded_and_close_releases_a_slot() {
    let mut runtime = NativeRuntime::default();
    let handles: Vec<_> = (0..MAX_NATIVE_SESSIONS).map(|_| create(&mut runtime)).collect();
    assert_eq!(runtime.create(plane(), &[(1, point(0.0, 0.0))]), Err(NativeError::CapacityExceeded));
    runtime.close(handles[0]).unwrap();
    let replacement = create(&mut runtime);
    assert!(!handles.contains(&replacement));
    assert_eq!(runtime.active_sessions(), MAX_NATIVE_SESSIONS);
}

#[test]
fn rejected_creation_does_not_consume_capacity() {
    let mut runtime = NativeRuntime::default();
    assert!(matches!(runtime.create(plane(), &[]), Err(NativeError::Create(_))));
    assert_eq!(runtime.active_sessions(), 0);
}

#[test]
fn closed_handle_cannot_update_verify_or_read() {
    let mut runtime = NativeRuntime::default();
    let handle = create(&mut runtime);
    runtime.close(handle).unwrap();
    assert_eq!(runtime.state(handle, 0), Err(NativeError::UnknownHandle));
    assert_eq!(runtime.guidance(handle, 0), Err(NativeError::UnknownHandle));
    assert_eq!(runtime.update_frame(handle, 0, true, &[], &[]), Err(NativeError::UnknownHandle));
    assert_eq!(runtime.begin_verification(handle, 0), Err(NativeError::UnknownHandle));
    assert_eq!(runtime.close(handle), Err(NativeError::UnknownHandle));
}

#[test]
fn close_all_and_other_runtimes_do_not_reuse_handles() {
    let mut first = NativeRuntime::default();
    let old = create(&mut first);
    first.close_all();
    assert_eq!(first.active_sessions(), 0);
    assert_eq!(first.state(old, 0), Err(NativeError::UnknownHandle));
    assert_ne!(create(&mut first), old);
    let mut second = NativeRuntime::default();
    let next = create(&mut second);
    assert_ne!(next, old);
    assert_eq!(second.state(old, 0), Err(NativeError::UnknownHandle));
}

#[test]
fn registry_delegates_the_real_core_and_isolates_verification_tickets() {
    let mut runtime = NativeRuntime::default();
    let first = create(&mut runtime);
    let second = create(&mut runtime);
    align(&mut runtime, first);
    align(&mut runtime, second);
    assert_eq!(runtime.state(first, 800), Ok(RestoreState::AwaitingVerification));
    let first_ticket = runtime.begin_verification(first, 800).unwrap();
    let second_ticket = runtime.begin_verification(second, 800).unwrap();
    assert!(matches!(runtime.complete_verification(second, first_ticket, 800,
        VerificationResult::Unavailable), Err(NativeError::Restore(_))));
    let verified = VerificationResult::Analyzed {
        overall: Verdict::Verified, objects: vec![(1, Verdict::Verified)],
    };
    assert_eq!(runtime.complete_verification(second, second_ticket, 800, verified),
        Ok(RestoreState::Verified));
    runtime.close(first).unwrap();
    assert_eq!(runtime.complete_verification(first, first_ticket, 800, VerificationResult::Unavailable),
        Err(NativeError::UnknownHandle));
}
