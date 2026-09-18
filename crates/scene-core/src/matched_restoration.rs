//! Frame-level entry point: assignment -> projection -> restoration -> guidance.
//! Callers supply same-session, timestamped evidence, not preselected matches.

use crate::guidance::{Guidance, GuidanceError};
use crate::local_restoration::TablePosition;
use crate::object_assignment::{assign_objects, AssignmentError, MatchDecision, PairEvidence};
use crate::projected_restoration::{
    ProjectedRestorationSession, ProjectedRestoreError, WorldObservation,
};
use crate::restoration_session::{
    RestoreError, RestoreState, VerificationResult, VerificationTicket,
};
use crate::table_projection::{TablePlane, WorldRay};

#[derive(Debug, Clone, Copy)]
pub struct CurrentDetection {
    pub current_id: u32,
    pub ray: WorldRay,
    pub occludes_other: bool,
}

#[derive(Debug, Clone, Copy)]
pub struct RestorationEvidence {
    pub pair: PairEvidence,
    // Orientation is relative to this saved object, not to a detection alone.
    pub orientation_aligned: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameError {
    TrackingUnavailable,
    NonIncreasingTimestamp,
    InvalidDetection,
    Assignment(AssignmentError),
    Projection(ProjectedRestoreError),
    InconsistentAssignment,
}

#[derive(Debug)]
pub struct MatchedRestorationSession {
    inner: ProjectedRestorationSession,
    saved_ids: Vec<u32>,
    associated_ids: Vec<Option<u32>>,
    last_frame_ms: Option<u64>,
}

impl MatchedRestorationSession {
    pub fn new(
        session_id: u128,
        plane: TablePlane,
        targets: &[(u32, TablePosition)],
    ) -> Result<Self, ProjectedRestoreError> {
        let inner = ProjectedRestorationSession::new(session_id, plane, targets)?;
        Ok(Self {
            inner,
            saved_ids: targets.iter().map(|(id, _)| *id).collect(),
            associated_ids: vec![None; targets.len()],
            last_frame_ms: None,
        })
    }

    /// now_ms is the frame's monotonic observation timestamp, not arrival time.
    /// A rejected batch invalidates all previous local and verification evidence.
    pub fn update_frame(
        &mut self,
        now_ms: u64,
        tracking_valid: bool,
        detections: &[CurrentDetection],
        evidence: &[RestorationEvidence],
    ) -> Result<RestoreState, FrameError> {
        if self.last_frame_ms.is_some_and(|previous| now_ms <= previous) {
            self.invalidate_frame(now_ms);
            return Err(FrameError::NonIncreasingTimestamp);
        }
        self.last_frame_ms = Some(now_ms);
        if !tracking_valid {
            self.invalidate_frame(now_ms);
            return Err(FrameError::TrackingUnavailable);
        }
        let updates = match self.prepare_frame(detections, evidence) {
            Ok(updates) => updates,
            Err(error) => {
                self.invalidate_frame(now_ms);
                return Err(error);
            }
        };
        for (index, mut observation, current_id) in updates {
            if self.associated_ids[index].is_some()
                && current_id.is_some()
                && self.associated_ids[index] != current_id
            {
                // Restart stability on an identity change. Do not submit two
                // observations at the same timestamp to bypass monotonicity.
                observation = WorldObservation::Unobserved;
            }
            if let Err(error) = self.inner.observe(self.saved_ids[index], now_ms, observation) {
                self.invalidate_frame(now_ms);
                return Err(FrameError::Projection(error));
            }
            self.associated_ids[index] = current_id;
        }
        Ok(self.inner.state(now_ms))
    }

    fn prepare_frame(
        &self,
        detections: &[CurrentDetection],
        evidence: &[RestorationEvidence],
    ) -> Result<Vec<(usize, WorldObservation, Option<u32>)>, FrameError> {
        if detections.len() > 5 {
            return Err(FrameError::Assignment(AssignmentError::InvalidObjectCount));
        }
        if evidence.len() > 25 {
            return Err(FrameError::Assignment(AssignmentError::TooManyPairs));
        }
        for detection in detections {
            let ray = detection.ray;
            let values = [
                ray.origin.x, ray.origin.y, ray.origin.z,
                ray.direction.x, ray.direction.y, ray.direction.z,
            ];
            let length = ray.direction.x.hypot(ray.direction.y).hypot(ray.direction.z);
            if values.iter().any(|value| !value.is_finite())
                || !length.is_finite() || length <= 0.0
            {
                return Err(FrameError::InvalidDetection);
            }
        }
        let current_ids: Vec<u32> = detections.iter().map(|item| item.current_id).collect();
        let pairs: Vec<PairEvidence> = evidence.iter().map(|item| item.pair).collect();
        let matches = assign_objects(&self.saved_ids, &current_ids, &pairs)
            .map_err(FrameError::Assignment)?;
        let mut updates = Vec::with_capacity(self.saved_ids.len());
        for matched in matches {
            let index = self.saved_ids.iter().position(|id| *id == matched.saved_id)
                .ok_or(FrameError::InconsistentAssignment)?;
            let (observation, current_id) = match matched.decision {
                MatchDecision::Accepted { current_id, score } => {
                    let detection = detections.iter().find(|item| item.current_id == current_id)
                        .ok_or(FrameError::InconsistentAssignment)?;
                    let pair = evidence.iter().find(|item| {
                        item.pair.saved_id == matched.saved_id
                            && item.pair.current_id == current_id
                    }).ok_or(FrameError::InconsistentAssignment)?;
                    (
                        WorldObservation::Located {
                            ray: detection.ray,
                            orientation_aligned: pair.orientation_aligned,
                            confidence: score,
                            occludes_other: detection.occludes_other,
                        },
                        Some(current_id),
                    )
                }
                MatchDecision::Ambiguous => (WorldObservation::Ambiguous, None),
                MatchDecision::Unmatched => (WorldObservation::Unobserved, None),
            };
            updates.push((index, observation, current_id));
        }
        Ok(updates)
    }

    fn invalidate_frame(&mut self, now_ms: u64) {
        self.associated_ids.fill(None);
        for &id in &self.saved_ids {
            // Even same/older timestamps invalidate local evidence through the
            // inner fail-closed clock handling. Keep the original frame error.
            let _ = self.inner.observe(id, now_ms, WorldObservation::TrackingLost);
        }
    }

    pub fn state(&self, now_ms: u64) -> RestoreState {
        self.inner.state(now_ms)
    }

    pub fn next_guidance(&self, now_ms: u64) -> Result<Option<Guidance>, GuidanceError> {
        self.inner.next_guidance(now_ms)
    }

    pub fn begin_verification(
        &mut self,
        now_ms: u64,
    ) -> Result<VerificationTicket, RestoreError> {
        self.inner.begin_verification(now_ms)
    }

    pub fn complete_verification(
        &mut self,
        ticket: VerificationTicket,
        now_ms: u64,
        result: VerificationResult,
    ) -> Result<RestoreState, RestoreError> {
        self.inner.complete_verification(ticket, now_ms, result)
    }
}
