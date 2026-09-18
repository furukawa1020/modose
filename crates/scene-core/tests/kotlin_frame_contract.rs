use scene_core::frame_ingress::decode_frame_packet;
use scene_core::pair_confidence::PairScore;

fn hex(text: &str) -> Vec<u8> {
    let compact: String = text.chars().filter(|c| !c.is_whitespace()).collect();
    assert_eq!(compact.len() % 2, 0);
    compact.as_bytes().chunks_exact(2)
        .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
        .collect()
}

#[test]
fn decodes_the_same_golden_packet_used_by_the_kotlin_encoder() {
    let bytes = hex(include_str!(
        "../../../apps/android/app/src/test/resources/core/frame-v1.hex"
    ));
    assert_eq!(bytes.len(), 98);
    let frame = decode_frame_packet(&bytes).unwrap();
    assert!(frame.tracking_valid);
    assert_eq!(frame.detections.len(), 1);
    assert_eq!(frame.evidence.len(), 1);
    let object = &frame.detections[0];
    assert_eq!(object.current_id, 0x01020304);
    assert_eq!((object.ray.origin.x, object.ray.origin.y, object.ray.origin.z), (1.0, 2.0, 3.0));
    assert_eq!((object.ray.direction.x, object.ray.direction.y, object.ray.direction.z), (0.5, -1.0, -0.25));
    assert!(object.occludes_other);
    let evidence = &frame.evidence[0];
    assert_eq!(evidence.pair.saved_id, 17);
    assert_eq!(evidence.pair.current_id, object.current_id);
    assert_eq!(evidence.pair.score, PairScore::new(0.75, 0.5, 1.0).unwrap());
    assert!(!evidence.orientation_aligned);
}

#[test]
fn decodes_kotlin_invalidation_as_tracking_lost_without_positions() {
    let bytes = hex(include_str!(
        "../../../apps/android/app/src/test/resources/core/tracking-lost-v1.hex"
    ));
    assert_eq!(bytes.len(), 12);
    let frame = decode_frame_packet(&bytes).unwrap();
    assert!(!frame.tracking_valid);
    assert!(frame.detections.is_empty());
    assert!(frame.evidence.is_empty());
}
