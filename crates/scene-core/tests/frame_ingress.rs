use scene_core::frame_ingress::*;
use scene_core::local_restoration::TablePosition;
use scene_core::native_runtime::*;
use scene_core::restoration_session::{RestoreState, VerificationResult};
use scene_core::table_projection::{TablePlane, WorldVector};

fn packet() -> Vec<u8> {
    let mut bytes = b"MDFR".to_vec();
    bytes.extend([0, 1, 1, 1, 1, 0, 0, 0]);
    bytes.extend(10u32.to_be_bytes());
    for value in [0.0f64, 1.0, 0.0, 0.0, -1.0, 0.0] { bytes.extend(value.to_be_bytes()); }
    bytes.push(0);
    bytes.extend(1u32.to_be_bytes());
    bytes.extend(10u32.to_be_bytes());
    for value in [1.0f64; 3] { bytes.extend(value.to_be_bytes()); }
    bytes.push(1);
    bytes
}

fn create(runtime: &mut NativeRuntime) -> SessionHandle {
    let v = |x, y, z| WorldVector { x, y, z };
    let p = |x, z| TablePosition { x, z };
    let plane = TablePlane::new(v(0.0, 0.0, 0.0), v(1.0, 0.0, 0.0), v(0.0, 0.0, 1.0),
        &[p(-1.0, -1.0), p(1.0, -1.0), p(1.0, 1.0), p(-1.0, 1.0)]).unwrap();
    runtime.create(plane, &[(1, p(0.0, 0.0))]).unwrap()
}

#[test]
fn valid_packet_decodes_big_endian_values() {
    let frame = decode_frame_packet(&packet()).unwrap();
    assert!(frame.tracking_valid);
    assert_eq!(frame.detections[0].current_id, 10);
    assert_eq!(frame.detections[0].ray.origin.y, 1.0);
    assert_eq!(frame.detections[0].ray.direction.y, -1.0);
    assert_eq!(frame.evidence[0].pair.saved_id, 1);
    assert!(frame.evidence[0].orientation_aligned);
}

#[test]
fn every_truncated_prefix_and_trailing_byte_are_rejected() {
    let valid = packet();
    for end in 0..valid.len() {
        assert!(decode_frame_packet(&valid[..end]).is_err(), "prefix {end}");
    }
    let mut trailing = valid;
    trailing.push(0);
    assert!(matches!(decode_frame_packet(&trailing), Err(FrameDecodeError::InvalidLength)));
    assert!(matches!(decode_frame_packet(&vec![0; MAX_FRAME_PACKET_BYTES + 1]),
        Err(FrameDecodeError::TooLarge)));
}

#[test]
fn header_and_boolean_fields_are_strict() {
    for (offset, value) in [(0, 0), (5, 2), (6, 6), (7, 26), (8, 2), (9, 1), (64, 2), (97, 2)] {
        let mut bytes = packet();
        bytes[offset] = value;
        assert!(decode_frame_packet(&bytes).is_err(), "offset {offset}");
    }
    let mut empty = b"MDFR".to_vec();
    empty.extend([0, 1, 0, 0, 1, 0, 0, 0]);
    let frame = decode_frame_packet(&empty).unwrap();
    assert!(frame.detections.is_empty());
    assert!(frame.evidence.is_empty());
}

#[test]
fn invalid_ids_numbers_and_scores_are_rejected() {
    for id in [0u32, u32::MAX] {
        let mut bytes = packet();
        bytes[12..16].copy_from_slice(&id.to_be_bytes());
        assert!(matches!(decode_frame_packet(&bytes), Err(FrameDecodeError::InvalidId)));
    }
    for value in [f64::NAN, f64::INFINITY] {
        let mut bytes = packet();
        bytes[16..24].copy_from_slice(&value.to_be_bytes());
        assert!(matches!(decode_frame_packet(&bytes), Err(FrameDecodeError::InvalidNumber)));
    }
    let mut bytes = packet();
    bytes[73..81].copy_from_slice(&1.1f64.to_be_bytes());
    assert!(matches!(decode_frame_packet(&bytes), Err(FrameDecodeError::InvalidScore)));
}

#[test]
fn malformed_packet_invalidates_only_its_owner_and_pending_verify() {
    let mut runtime = NativeRuntime::default();
    let first = create(&mut runtime);
    let second = create(&mut runtime);
    for time in (0..=800).step_by(100) {
        apply_frame_packet(&mut runtime, first, time, &packet()).unwrap();
        apply_frame_packet(&mut runtime, second, time, &packet()).unwrap();
    }
    let ticket = runtime.begin_verification(first, 800).unwrap();
    assert!(matches!(apply_frame_packet(&mut runtime, first, 900, b"bad"),
        Err(FrameIngressError::Decode(_))));
    assert_eq!(runtime.state(first, 900), Ok(RestoreState::Guiding));
    assert_eq!(runtime.state(second, 900), Ok(RestoreState::AwaitingVerification));
    assert!(runtime.complete_verification(first, ticket, 900, VerificationResult::Unavailable).is_err());
}

#[test]
fn unknown_handles_and_duplicate_timestamps_fail_closed() {
    let mut runtime = NativeRuntime::default();
    let handle = create(&mut runtime);
    for time in (0..=800).step_by(100) {
        apply_frame_packet(&mut runtime, handle, time, &packet()).unwrap();
    }
    assert!(apply_frame_packet(&mut runtime, handle, 800, &packet()).is_err());
    assert_eq!(runtime.state(handle, 800), Ok(RestoreState::Guiding));
    runtime.close(handle).unwrap();
    assert!(matches!(apply_frame_packet(&mut runtime, handle, 900, &packet()),
        Err(FrameIngressError::Runtime(NativeError::UnknownHandle))));
}
