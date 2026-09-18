use scene_core::guidance::*;
use scene_core::local_restoration::*;
use scene_core::matched_restoration::*;
use scene_core::object_assignment::*;
use scene_core::pair_confidence::*;
use scene_core::restoration_session::*;
use scene_core::table_projection::*;

fn point(x: f64, z: f64) -> TablePosition { TablePosition { x, z } }
fn vector(x: f64, y: f64, z: f64) -> WorldVector { WorldVector { x, y, z } }
fn located(x: f64) -> Observation {
    Observation::Located { position: point(x, 0.0), orientation_aligned: true }
}
fn plane() -> TablePlane {
    TablePlane::new(vector(0.0, 0.0, 0.0), vector(1.0, 0.0, 0.0),
        vector(0.0, 0.0, 1.0), &[
            point(-1.0, -1.0), point(1.0, -1.0),
            point(1.0, 1.0), point(-1.0, 1.0),
        ]).unwrap()
}
fn ray(x: f64) -> WorldRay {
    WorldRay { origin: vector(x, 1.0, 0.0), direction: vector(0.0, -1.0, 0.0) }
}
fn pair(saved_id: u32, current_id: u32, score: f64) -> PairEvidence {
    PairEvidence { saved_id, current_id, score: PairScore::new(score, score, score).unwrap() }
}
fn aligned_session() -> RestorationSession {
    let mut session = RestorationSession::new(1, &[(1, point(0.0, 0.0))]).unwrap();
    for time in (0..=800).step_by(100) { session.observe(1, time, located(0.0)).unwrap(); }
    session
}
fn verified() -> VerificationResult {
    VerificationResult::Analyzed { overall: Verdict::Verified, objects: vec![(1, Verdict::Verified)] }
}

#[test]
fn local_alignment_requires_800ms_and_uses_distance_hysteresis() {
    let mut local = LocalRestoration::new(point(0.0, 0.0)).unwrap();
    for time in (0..800).step_by(100) {
        assert_eq!(local.observe(time, located(0.03)), Ok(LocalState::Stabilizing));
    }
    assert_eq!(local.observe(800, located(0.03)), Ok(LocalState::Aligned));
    assert_eq!(local.observe(900, located(0.049)), Ok(LocalState::Aligned));
    assert_eq!(local.observe(1000, located(0.05)), Ok(LocalState::Located));
}

#[test]
fn interruption_and_long_gap_reset_stability() {
    for interrupted in [Observation::TrackingLost, Observation::Missing, Observation::Ambiguous] {
        let mut local = LocalRestoration::new(point(0.0, 0.0)).unwrap();
        for time in (0..=700).step_by(100) { local.observe(time, located(0.0)).unwrap(); }
        local.observe(800, interrupted).unwrap();
        assert_eq!(local.observe(900, located(0.0)), Ok(LocalState::Stabilizing));
    }
    let mut local = LocalRestoration::new(point(0.0, 0.0)).unwrap();
    local.observe(0, located(0.0)).unwrap();
    assert_eq!(local.observe(800, located(0.0)), Ok(LocalState::Stabilizing));
}

#[test]
fn invalid_positions_clocks_and_orientation_never_align() {
    assert!(LocalRestoration::new(point(f64::NAN, 0.0)).is_err());
    let mut local = LocalRestoration::new(point(0.0, 0.0)).unwrap();
    local.observe(10, located(0.0)).unwrap();
    assert_eq!(local.observe(10, located(0.0)), Err(LocalError::NonIncreasingTimestamp));
    assert_eq!(local.observe(11, located(f64::INFINITY)), Err(LocalError::InvalidPosition));
    for time in (100..=1000).step_by(100) {
        local.observe(time, Observation::Located {
            position: point(0.0, 0.0), orientation_aligned: false,
        }).unwrap();
        assert!(!local.is_aligned_at(time));
    }
}

#[test]
fn local_freshness_has_a_closed_200ms_boundary() {
    let mut local = LocalRestoration::new(point(0.0, 0.0)).unwrap();
    for time in (0..=800).step_by(100) { local.observe(time, located(0.0)).unwrap(); }
    assert!(local.is_aligned_at(1000));
    assert!(!local.is_aligned_at(1001));
    assert!(!local.is_aligned_at(799));
}

#[test]
fn sessions_reject_invalid_object_sets() {
    for targets in [vec![], vec![(0, point(0.0, 0.0))],
        vec![(1, point(0.0, 0.0)), (1, point(0.1, 0.0))],
        (1..=6).map(|id| (id, point(0.0, 0.0))).collect()] {
        assert!(RestorationSession::new(1, &targets).is_err());
    }
    assert!(RestorationSession::new(0, &[(1, point(0.0, 0.0))]).is_err());
}

#[test]
fn only_current_verified_response_allows_success() {
    let mut session = aligned_session();
    assert_eq!(session.state(800), RestoreState::AwaitingVerification);
    let ticket = session.begin_verification(800).unwrap();
    assert_eq!(session.state(800), RestoreState::Verifying);
    assert_eq!(session.complete_verification(ticket, 800, verified()), Ok(RestoreState::Verified));
    assert_eq!(session.complete_verification(ticket, 800, verified()), Err(RestoreError::StaleVerification));
    assert_eq!(session.state(1001), RestoreState::Guiding);
}

#[test]
fn tracking_loss_rejects_in_flight_and_cross_session_tickets() {
    let mut session = aligned_session();
    let ticket = session.begin_verification(800).unwrap();
    session.observe(1, 900, Observation::TrackingLost).unwrap();
    assert_eq!(session.complete_verification(ticket, 900, verified()), Err(RestoreError::StaleVerification));
    let mut other = RestorationSession::new(2, &[(1, point(0.0, 0.0))]).unwrap();
    for time in (0..=800).step_by(100) { other.observe(1, time, located(0.0)).unwrap(); }
    other.begin_verification(800).unwrap();
    assert_eq!(other.complete_verification(ticket, 800, verified()), Err(RestoreError::StaleVerification));
}

#[test]
fn invalid_verification_object_sets_fail_closed() {
    for objects in [vec![], vec![(2, Verdict::Verified)],
        vec![(1, Verdict::Verified), (1, Verdict::Verified)]] {
        let mut session = aligned_session();
        let ticket = session.begin_verification(800).unwrap();
        let result = VerificationResult::Analyzed { overall: Verdict::Verified, objects };
        assert_eq!(session.complete_verification(ticket, 800, result), Err(RestoreError::InvalidVerificationObjects));
        assert_eq!(session.state(800), RestoreState::Guiding);
    }
}

#[test]
fn uncertain_correction_and_three_failures_are_not_success() {
    for verdict in [Verdict::Uncertain, Verdict::NeedsCorrection] {
        let mut session = aligned_session();
        let ticket = session.begin_verification(800).unwrap();
        let response = VerificationResult::Analyzed {
            overall: Verdict::Verified, objects: vec![(1, verdict)],
        };
        assert_ne!(session.complete_verification(ticket, 800, response).unwrap(), RestoreState::Verified);
    }
    let mut session = aligned_session();
    for attempt in 1..=3 {
        let ticket = session.begin_verification(800).unwrap();
        session.complete_verification(ticket, 800, VerificationResult::Unavailable).unwrap();
        assert_eq!(session.verification_attempts(), attempt);
    }
    assert_eq!(session.state(800), RestoreState::ManualConfirmation);
    assert_eq!(session.begin_verification(800), Err(RestoreError::AttemptsExhausted));
}

#[test]
fn projection_rejects_parallel_behind_outside_and_nonfinite_rays() {
    let plane = plane();
    assert_eq!(plane.project(ray(0.2)).unwrap(), point(0.2, 0.0));
    assert!(plane.contains(point(1.0, 1.0)));
    assert_eq!(plane.project(ray(2.0)), Err(ProjectionError::OutsidePlane));
    let mut invalid = ray(0.0);
    invalid.direction = vector(1.0, 0.0, 0.0);
    assert_eq!(plane.project(invalid), Err(ProjectionError::ParallelRay));
    invalid.direction = vector(0.0, 1.0, 0.0);
    assert_eq!(plane.project(invalid), Err(ProjectionError::BehindRay));
    invalid.direction = vector(0.0, 0.0, 0.0);
    assert_eq!(plane.project(invalid), Err(ProjectionError::InvalidRay));
    assert!(plane.project(ray(f64::NAN)).is_err());
}

#[test]
fn rotated_translated_plane_and_polygon_order_are_supported() {
    let polygon = [point(-1.0, -1.0), point(1.0, -1.0), point(1.0, 1.0), point(-1.0, 1.0)];
    for vertices in [polygon.to_vec(), polygon.into_iter().rev().collect()] {
        let plane = TablePlane::new(vector(2.0, 0.0, 3.0), vector(0.0, 0.0, 1.0),
            vector(-1.0, 0.0, 0.0), &vertices).unwrap();
        let projected = plane.project(WorldRay {
            origin: vector(2.0, 1.0, 3.5), direction: vector(0.0, -2.0, 0.0),
        }).unwrap();
        assert_eq!(projected, point(0.5, 0.0));
    }
}

#[test]
fn invalid_polygons_and_bases_are_rejected() {
    for polygon in [
        vec![point(0.0, 0.0); 3],
        vec![point(0.0, 0.0), point(1.0, 0.0), point(2.0, 0.0)],
        vec![point(-1.0, -1.0), point(1.0, 1.0), point(-1.0, 1.0), point(1.0, -1.0)],
    ] {
        assert!(TablePlane::new(vector(0.0, 0.0, 0.0), vector(1.0, 0.0, 0.0),
            vector(0.0, 0.0, 1.0), &polygon).is_err());
    }
}

#[test]
fn pair_scores_validate_fields_and_thresholds() {
    for invalid in [f64::NAN, f64::INFINITY, -0.1, 1.1] {
        assert!(PairScore::new(invalid, 1.0, 1.0).is_err());
        assert!(PairScore::new(1.0, invalid, 1.0).is_err());
        assert!(PairScore::new(1.0, 1.0, invalid).is_err());
    }
    assert_eq!(PairScore::new(0.5, 0.5, 0.5).unwrap().band(), ScoreBand::Rejected);
    assert_eq!(PairScore::new(0.7, 0.7, 0.7).unwrap().band(), ScoreBand::Ambiguous);
    assert_eq!(PairScore::new(0.78, 0.78, 0.78).unwrap().band(), ScoreBand::Eligible);
}

#[test]
fn assignment_is_unique_order_independent_and_allows_unmatched() {
    let evidence = [pair(1, 10, 1.0), pair(2, 20, 1.0)];
    let expected = assign_objects(&[1, 2], &[10, 20], &evidence).unwrap();
    assert_eq!(expected, assign_objects(&[2, 1], &[20, 10], &evidence.into_iter().rev().collect::<Vec<_>>()).unwrap());
    assert!(matches!(expected[0].decision, MatchDecision::Accepted { current_id: 10, .. }));
    assert!(matches!(expected[1].decision, MatchDecision::Accepted { current_id: 20, .. }));
    assert_eq!(assign_objects(&[1], &[], &[]).unwrap()[0].decision, MatchDecision::Unmatched);
}

#[test]
fn assignment_ties_competition_and_invalid_ids_are_not_accepted() {
    let ties = [pair(1, 10, 1.0), pair(1, 20, 1.0), pair(2, 10, 1.0), pair(2, 20, 1.0)];
    assert!(assign_objects(&[1, 2], &[10, 20], &ties).unwrap().iter()
        .all(|matched| matched.decision == MatchDecision::Ambiguous));
    assert!(assign_objects(&[1, 1], &[10], &[]).is_err());
    assert!(assign_objects(&[1], &[10, 10], &[]).is_err());
    assert!(assign_objects(&[1], &[10], &[pair(2, 10, 1.0)]).is_err());
    assert!(assign_objects(&[1], &[10], &[pair(1, 10, 1.0); 2]).is_err());
}

fn sample(id: u32, distance: f64, confidence: f64, occludes: bool) -> GuidanceSample {
    GuidanceSample { object_id: id, target: point(0.0, 0.0), current: Some(point(distance, 0.0)),
        observed_at_ms: Some(100), local_state: LocalState::Located, confidence,
        occludes_other: occludes, orientation_aligned: true }
}

#[test]
fn guidance_prioritizes_occlusion_confidence_distance_then_id() {
    let mut samples = [sample(1, 0.1, 1.0, false), sample(2, 0.5, 0.8, true)];
    assert_eq!(plan_guidance(100, &samples).unwrap().unwrap().object_id, 2);
    samples[1].occludes_other = false;
    assert_eq!(plan_guidance(100, &samples).unwrap().unwrap().object_id, 1);
    samples[1].confidence = 1.0;
    samples[1].current = Some(point(0.05, 0.0));
    assert_eq!(plan_guidance(100, &samples).unwrap().unwrap().object_id, 2);
    samples[1].current = samples[0].current;
    assert_eq!(plan_guidance(100, &samples).unwrap().unwrap().object_id, 1);
}

#[test]
fn guidance_uses_ring_orientation_or_positionless_recovery() {
    let mut item = sample(1, 0.03, 1.0, false);
    assert!(matches!(plan_guidance(100, &[item]).unwrap().unwrap().action, GuidanceAction::Ring { .. }));
    item.orientation_aligned = false;
    assert!(matches!(plan_guidance(100, &[item]).unwrap().unwrap().action, GuidanceAction::CheckOrientation { .. }));
    assert!(matches!(plan_guidance(301, &[item]).unwrap().unwrap().action, GuidanceAction::Recover { .. }));
    assert_eq!(plan_guidance(99, &[item]), Err(GuidanceError::InvalidTimestamp));
}

fn detection(id: u32, x: f64) -> CurrentDetection {
    CurrentDetection { current_id: id, ray: ray(x), occludes_other: false }
}
fn evidence(id: u32, current: u32) -> RestorationEvidence {
    RestorationEvidence { pair: pair(id, current, 1.0), orientation_aligned: true }
}
fn matched() -> MatchedRestorationSession {
    MatchedRestorationSession::new(7, plane(), &[(1, point(0.0, 0.0))]).unwrap()
}

#[test]
fn matched_frame_drives_guidance_stability_and_verification() {
    let mut session = matched();
    session.update_frame(0, true, &[detection(10, 0.2)], &[evidence(1, 10)]).unwrap();
    assert!(matches!(session.next_guidance(0).unwrap().unwrap().action, GuidanceAction::Move { .. }));
    for time in (100..=900).step_by(100) {
        session.update_frame(time, true, &[detection(10, 0.0)], &[evidence(1, 10)]).unwrap();
    }
    assert_eq!(session.state(900), RestoreState::AwaitingVerification);
    let ticket = session.begin_verification(900).unwrap();
    assert_eq!(session.complete_verification(ticket, 900, verified()), Ok(RestoreState::Verified));
}

#[test]
fn identity_change_and_invalid_batch_remove_old_guidance() {
    let mut session = matched();
    for time in (0..=800).step_by(100) {
        session.update_frame(time, true, &[detection(10, 0.0)], &[evidence(1, 10)]).unwrap();
    }
    let ticket = session.begin_verification(800).unwrap();
    session.update_frame(900, true, &[detection(20, 0.0)], &[evidence(1, 20)]).unwrap();
    assert_eq!(session.state(900), RestoreState::Guiding);
    assert_eq!(session.complete_verification(ticket, 900, verified()), Err(RestoreError::StaleVerification));
    assert!(session.update_frame(1000, true, &[detection(20, 0.0); 2], &[]).is_err());
    assert!(matches!(session.next_guidance(1000).unwrap().unwrap().action, GuidanceAction::Recover { .. }));
}

#[test]
fn partial_projection_failure_invalidates_entire_frame() {
    let mut session = MatchedRestorationSession::new(7, plane(),
        &[(1, point(0.0, 0.0)), (2, point(0.5, 0.0))]).unwrap();
    let pairs = [evidence(1, 10), evidence(2, 20)];
    for time in (0..=800).step_by(100) {
        session.update_frame(time, true, &[detection(10, 0.0), detection(20, 0.5)], &pairs).unwrap();
    }
    let ticket = session.begin_verification(800).unwrap();
    assert!(session.update_frame(900, true, &[detection(10, 0.0), detection(20, 2.0)], &pairs).is_err());
    assert_eq!(session.state(900), RestoreState::Guiding);
    assert!(session.complete_verification(ticket, 900, VerificationResult::Unavailable).is_err());
    assert!(matches!(session.next_guidance(900).unwrap().unwrap().action, GuidanceAction::Recover { .. }));
}
